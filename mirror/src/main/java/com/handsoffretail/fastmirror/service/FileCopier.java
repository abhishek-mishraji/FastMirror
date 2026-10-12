package com.handsoffretail.fastmirror.service;

import com.handsoffretail.fastmirror.entity.CopyJob;
import com.handsoffretail.fastmirror.entity.CopyResult;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Performs one copy: open the source with retry, transfer, stamp the timestamp, publish atomically.
 *
 * <p><strong>The sole custodian of the non-interference rules.</strong> Its implementation is the
 * only class in the codebase permitted to open a file inside the source folder, which is what makes
 * those rules reviewable in one place and enforceable by AT-02.
 */
public interface FileCopier {

    /**
     * Copy one file, absorbing every failure into a {@link CopyResult}.
     *
     * @return the outcome; never {@code null}, and this method never throws
     */
    CopyResult copy(CopyJob job);

    /**
     * Reads the start of a source file, for the business date written inside it.
     *
     * <p>It lives here, not in the date logic, because opening a source file is this class's
     * exclusive privilege: the read uses the same read-only, fully shared open as a copy, so the
     * producer can still write, rename and delete the file while it happens.
     *
     * @return at most {@code maxBytes} from the start of the file, or empty if it could not be read
     *         right now (gone or locked); this method never throws
     */
    Optional<byte[]> readHead(Path relative, int maxBytes);
}
