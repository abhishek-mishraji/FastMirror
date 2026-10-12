package com.handsoffretail.fastmirror.service.implementation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.handsoffretail.fastmirror.config.MirrorConfig;
import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyResult;
import com.handsoffretail.fastmirror.entity.LedgerKey;
import com.handsoffretail.fastmirror.logging.AppLogger;
import com.handsoffretail.fastmirror.service.FileCopier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDateResolverImplTest {

    @TempDir Path sourceRoot;
    @TempDir Path mirrorRoot;
    @TempDir Path baseDirectory;

    private static final Path FILE = Path.of("FGM3402610010009241533201.xml");
    private static final FileTime CREATED = FileTime.from(Instant.parse("2026-10-01T04:09:24Z"));
    private static final LedgerKey KEY = LedgerKey.of(FILE, CREATED.toMillis(), 685L);

    private AppLogger logger;
    private final Map<String, String> values = new HashMap<>();

    /** Serves queued answers to readHead and counts the calls. */
    private static final class FakeCopier implements FileCopier {
        private final Deque<Optional<byte[]>> answers = new ArrayDeque<>();
        private final AtomicInteger reads = new AtomicInteger();

        void answer(String text) {
            answers.add(Optional.of(text.getBytes(StandardCharsets.ISO_8859_1)));
        }

        void unreadable() {
            answers.add(Optional.empty());
        }

        @Override
        public CopyResult copy(CopyJob job) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> readHead(Path relative, int maxBytes) {
            reads.incrementAndGet();
            return answers.isEmpty() ? Optional.empty() : answers.poll();
        }
    }

    private final FakeCopier copier = new FakeCopier();

    @BeforeEach
    void setUp() {
        logger = new AppLogger(baseDirectory.resolve("logs"), Clock.systemDefaultZone());
        values.put(MirrorConfig.KEY_SOURCE_FOLDER, sourceRoot.toString());
        values.put(MirrorConfig.KEY_MIRROR_FOLDER, mirrorRoot.toString());
        values.put(MirrorConfig.KEY_INCLUDE_PREFIXES, "FGM");
        values.put(MirrorConfig.KEY_DATE_SOURCE, "CONTENT");
        values.put(MirrorConfig.KEY_CONTENT_DATE_FIELDS, "BusinessDate");
    }

    @AfterEach
    void tearDown() {
        logger.close();
    }

    private FileDateResolverImpl resolver() {
        return new FileDateResolverImpl(MirrorConfig.load(values::get, baseDirectory), copier, logger);
    }

    @Test
    @DisplayName("month end: a report written 1 Oct for business day 30 Sept is dated 30 Sept")
    void monthEnd() {
        copier.answer("<MovementHeader><BusinessDate>2026-09-30</BusinessDate><EndDate>2026-10-01</EndDate>");
        final FileDateResolverImpl resolver = resolver();

        assertEquals(Optional.empty(), resolver.knownDate(FILE, KEY, CREATED), "not read yet");
        assertEquals(Optional.of(LocalDate.of(2026, 9, 30)), resolver.dateOf(FILE, KEY, CREATED));
    }

    @Test
    @DisplayName("a file's content is read once; afterwards its date is known without reading")
    void readOnce() {
        copier.answer("<BusinessDate>2026-09-30</BusinessDate>");
        final FileDateResolverImpl resolver = resolver();

        resolver.dateOf(FILE, KEY, CREATED);
        resolver.dateOf(FILE, KEY, CREATED);

        assertEquals(1, copier.reads.get());
        assertEquals(Optional.of(LocalDate.of(2026, 9, 30)), resolver.knownDate(FILE, KEY, CREATED));
    }

    @Test
    @DisplayName("a file that cannot be read right now is not given a date, and is read again later")
    void unreadableIsRetried() {
        copier.unreadable();
        copier.answer("<BusinessDate>2026-09-30</BusinessDate>");
        final FileDateResolverImpl resolver = resolver();

        assertEquals(Optional.empty(), resolver.dateOf(FILE, KEY, CREATED));
        assertEquals(Optional.of(LocalDate.of(2026, 9, 30)), resolver.dateOf(FILE, KEY, CREATED));
    }

    @Test
    @DisplayName("a file without the field falls back to its creation date in TIME_ZONE")
    void fallsBackToCreated() {
        copier.answer("<ACKHEADER><STATUSCODE>Success</STATUSCODE></ACKHEADER>");

        // 04:09 UTC on 1 Oct is 00:09 on 1 Oct in New York.
        assertEquals(Optional.of(LocalDate.of(2026, 10, 1)), resolver().dateOf(FILE, KEY, CREATED));
    }

    @Test
    @DisplayName("remembered dates are dropped once a file is no longer waiting")
    void retainOnly() {
        copier.answer("<BusinessDate>2026-09-30</BusinessDate>");
        final FileDateResolverImpl resolver = resolver();
        resolver.dateOf(FILE, KEY, CREATED);

        resolver.retainOnly(Set.of());

        assertEquals(Optional.empty(), resolver.knownDate(FILE, KEY, CREATED));
    }

    @Test
    @DisplayName("BUSINESS_DAY_STARTS_AT moves an after-midnight file-name time to the day before")
    void businessDayStartsAtForFileNames() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "FILENAME");
        values.remove(MirrorConfig.KEY_CONTENT_DATE_FIELDS);
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "*yyyyMMddHHmmss.pdf");
        values.put(MirrorConfig.KEY_BUSINESS_DAY_STARTS_AT, "03:00");
        final FileDateResolverImpl resolver = resolver();

        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 30)),
                resolver.dateOf(Path.of("StoreClose20261001001017.pdf"), KEY, CREATED),
                "printed 00:10 on 1 Oct: business day 30 Sept");
        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 23)),
                resolver.dateOf(Path.of("ShiftClose20260923122815.pdf"), KEY, CREATED),
                "printed 12:28: the same day");
        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 23)),
                resolver.dateOf(Path.of("StoreClose20260923030000.pdf"), KEY, CREATED),
                "exactly 03:00 already belongs to the new day");
    }

    @Test
    @DisplayName("BUSINESS_DAY_STARTS_AT applies to creation times, never to dates inside the file")
    void businessDayStartsAtForCreatedOnly() {
        values.put(MirrorConfig.KEY_BUSINESS_DAY_STARTS_AT, "03:00");
        copier.answer("<BusinessDate>2026-10-01</BusinessDate>");
        assertEquals(
                Optional.of(LocalDate.of(2026, 10, 1)),
                resolver().dateOf(FILE, KEY, CREATED),
                "content dates are business dates already");

        values.put(MirrorConfig.KEY_DATE_SOURCE, "CREATED");
        values.remove(MirrorConfig.KEY_CONTENT_DATE_FIELDS);
        assertEquals(
                Optional.of(LocalDate.of(2026, 9, 30)),
                resolver().dateOf(FILE, KEY, CREATED),
                "created 00:09 on 1 Oct New York time: the day before");
    }

    @Test
    @DisplayName("CREATED and FILENAME never read the file")
    void otherSourcesDoNotRead() {
        values.put(MirrorConfig.KEY_DATE_SOURCE, "CREATED");
        values.remove(MirrorConfig.KEY_CONTENT_DATE_FIELDS);
        assertEquals(Optional.of(LocalDate.of(2026, 10, 1)), resolver().dateOf(FILE, KEY, CREATED));

        values.put(MirrorConfig.KEY_DATE_SOURCE, "FILENAME");
        values.put(MirrorConfig.KEY_FILENAME_DATE_PATTERN, "??????yyMMdd*");
        assertEquals(Optional.of(LocalDate.of(2026, 10, 1)), resolver().dateOf(FILE, KEY, CREATED));

        assertEquals(0, copier.reads.get());
    }
}
