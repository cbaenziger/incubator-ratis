/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import net.jodah.failsafe.function.CheckedRunnable;
import net.jodah.failsafe.function.CheckedSupplier;

import java.io.*;
import java.time.Duration;
import java.util.List;

/**
 * A FileOutputStream that has the property that it will only show
 * up at its destination once it has been entirely written and flushed
 * to disk. While being written, it will use a .tmp suffix.
 *
 * When the output stream is closed, it is flushed, fsynced, and
 * will be moved into place, overwriting any file that already
 * exists at that location.
 *
 * <b>NOTE</b>: on Windows platforms, it will not atomically
 * replace the target file - instead the target file is deleted
 * before this one is moved into place.
 */
public class AtomicFileOutputStream extends FilterOutputStream {

  public static final String TMP_EXTENSION = ".tmp";

  public static final Logger LOG = LoggerFactory.getLogger(AtomicFileOutputStream.class);

  private final File origFile;
  private final File tmpFile;

  private static final RetryPolicy<Void> retryPolicy = new RetryPolicy<Void>()
      .handle(IOException.class)
      .onRetry(e -> LOG.warn("Retrying:", (e.getLastFailure() != null ? e.getLastFailure() :e.getLastResult())))
      .onFailure(e -> LOG.error("Failed:", e.getFailure()))
      .withDelay(Duration.ofNanos(1))
      .withMaxAttempts(-1);

  private static final RetryPolicy<Boolean> booleanCheckingRetryPolicy = new RetryPolicy<Boolean>()
      .handle(IOException.class)
      .handleResult(false)
      .onRetry(e -> LOG.warn("Retrying boolean:", e.getLastFailure()))
      .onFailure(e -> LOG.error("Failed to get true for:", e.getFailure()))
      .withDelay(Duration.ofNanos(1))
      .withMaxAttempts(-1);

  public AtomicFileOutputStream(File f) throws FileNotFoundException {
    // Code unfortunately must be duplicated below since we can't assign anything
    // before calling super
    super(new FileOutputStream(new File(f.getParentFile(), f.getName() + TMP_EXTENSION)));
    origFile = f.getAbsoluteFile();
    tmpFile = new File(f.getParentFile(), f.getName() + TMP_EXTENSION).getAbsoluteFile();
  }

  @Override
  public void close() throws IOException {
    boolean triedToClose = false, success = false;
    try {
      // WARNING: We try a LOT more than 5 times for this function as each step is
      // retried independently to keep the original code flow
      Failsafe.with(retryPolicy).run((CheckedRunnable)()->{
        flush();
        ((FileOutputStream)out).getChannel().force(true);
      });
      triedToClose = true;
      Failsafe.with(retryPolicy).run((CheckedRunnable)()->{
        super.close();
      });
      success = true;
    } finally {
      if (success) {
        boolean renamed = Failsafe.with(booleanCheckingRetryPolicy).get((CheckedSupplier<Boolean>)()->{
          return(tmpFile.renameTo(origFile));
        });
        if (!renamed) {
          // On windows, renameTo does not replace.
          boolean exists = Failsafe.with(booleanCheckingRetryPolicy).get((CheckedSupplier<Boolean>)(origFile::exists));
          if (exists && !Failsafe.with(booleanCheckingRetryPolicy).get((CheckedSupplier<Boolean>)(origFile::delete))) {
            throw new IOException("Could not delete original file " + origFile);
          }
          Failsafe.with(retryPolicy).run((CheckedRunnable)()->{
            FileUtils.move(tmpFile, origFile);
          });
        }
      } else {
        if (!triedToClose) {
          // If we failed when flushing, try to close it to not leak an FD
          Failsafe.with(retryPolicy).run((CheckedRunnable)()->{
            IOUtils.cleanup(LOG, out);
          });
        }
        // close wasn't successful, try to delete the tmp file
        if (!Failsafe.with(booleanCheckingRetryPolicy).get((CheckedSupplier<Boolean>)(tmpFile::delete))) {
          LOG.warn("Unable to delete tmp file " + tmpFile);
        }
      }
    }
  }

  /**
   * Close the atomic file, but do not "commit" the temporary file
   * on top of the destination. This should be used if there is a failure
   * in writing.
   */
  public void abort() {
    Failsafe.with(retryPolicy).run((CheckedRunnable)(super::close));
    if (!Failsafe.with(booleanCheckingRetryPolicy).get((CheckedSupplier<Boolean>)(tmpFile::delete))) {
      LOG.warn("Unable to delete tmp file during abort " + tmpFile);
    }
  }

}
