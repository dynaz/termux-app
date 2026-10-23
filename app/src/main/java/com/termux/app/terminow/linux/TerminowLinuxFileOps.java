package com.termux.app.terminow.linux;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * The file operations that keep Linux semantics, which java.io.File does not have on Android
 * before 8: lstat, links and permission bits. An interface, so the tests run with java.nio.
 */
public interface TerminowLinuxFileOps extends TerminowLinuxPaths.Links {

    class Stat {
        public boolean directory;
        public boolean link;
        public boolean regular;
        public int mode;
        public long size;
        public long modified;
        /** Bytes on disk, which a sparse file has fewer of than its size. */
        public long allocated;
    }

    /** The entry itself, never what a link points to, {@code null} if there is none. */
    @Nullable Stat lstat(@NonNull File file);

    void symlink(@NonNull String target, @NonNull File link) throws IOException;

    void chmod(@NonNull File file, int mode) throws IOException;

    /** On Android, with android.system.Os. */
    class Android implements TerminowLinuxFileOps {
        @Override
        public boolean isLink(@NonNull File file) {
            try {
                return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
            } catch (ErrnoException e) {
                return false;
            }
        }

        @NonNull
        @Override
        public String readLink(@NonNull File file) throws IOException {
            try {
                return Os.readlink(file.getPath());
            } catch (ErrnoException e) {
                throw new IOException(e.getMessage());
            }
        }

        @Nullable
        @Override
        public Stat lstat(@NonNull File file) {
            try {
                StructStat st = Os.lstat(file.getPath());
                Stat stat = new Stat();
                stat.link = OsConstants.S_ISLNK(st.st_mode);
                stat.directory = OsConstants.S_ISDIR(st.st_mode);
                stat.regular = OsConstants.S_ISREG(st.st_mode);
                stat.mode = st.st_mode & 07777;
                stat.size = st.st_size;
                stat.modified = st.st_mtime * 1000L;
                stat.allocated = st.st_blocks * 512L;
                return stat;
            } catch (ErrnoException e) {
                return null;
            }
        }

        @Override
        public void symlink(@NonNull String target, @NonNull File link) throws IOException {
            try {
                Os.symlink(target, link.getPath());
            } catch (ErrnoException e) {
                throw new IOException("Could not make the link " + link.getName() + ": " + e.getMessage());
            }
        }

        @Override
        public void chmod(@NonNull File file, int mode) throws IOException {
            try {
                Os.chmod(file.getPath(), mode & 07777);
            } catch (ErrnoException e) {
                throw new IOException("Could not set the permissions of " + file.getName() + ": " + e.getMessage());
            }
        }
    }

}
