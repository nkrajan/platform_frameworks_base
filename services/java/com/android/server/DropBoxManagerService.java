/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Time;
import android.util.Log;

import com.android.internal.os.IDropBoxManagerService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * Implementation of {@link IDropBoxManagerService} using the filesystem.
 * Clients use {@link DropBoxManager} to access this service.
 */
public final class DropBoxManagerService extends IDropBoxManagerService.Stub {
    private static final String TAG = "DropBoxManagerService";
    private static final int DEFAULT_RESERVE_PERCENT = 10;
    private static final int DEFAULT_QUOTA_PERCENT = 10;
    private static final int DEFAULT_QUOTA_KB = 5 * 1024;
    private static final int DEFAULT_AGE_SECONDS = 3 * 86400;
    private static final int QUOTA_RESCAN_MILLIS = 5000;

    private static final boolean PROFILE_DUMP = false;

    // TODO: This implementation currently uses one file per entry, which is
    // inefficient for smallish entries -- consider using a single queue file
    // per tag (or even globally) instead.

    // The cached context and derived objects

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final File mDropBoxDir;

    // Accounting of all currently written log files (set in init()).

    private FileList mAllFiles = null;
    private HashMap<String, FileList> mFilesByTag = null;

    // Various bits of disk information

    private StatFs mStatFs = null;
    private int mBlockSize = 0;
    private int mCachedQuotaBlocks = 0;  // Space we can use: computed from free space, etc.
    private long mCachedQuotaUptimeMillis = 0;

    // Ensure that all log entries have a unique timestamp
    private long mLastTimestamp = 0;

    /** Receives events that might indicate a need to clean up files. */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mCachedQuotaUptimeMillis = 0;  // Force a re-check of quota size
            try {
                init();
                trimToFit();
            } catch (IOException e) {
                Log.e(TAG, "Can't init", e);
            }
        }
    };

    /**
     * Creates an instance of managed drop box storage.  Normally there is one of these
     * run by the system, but others can be created for testing and other purposes.
     *
     * @param context to use for receiving free space & gservices intents
     * @param path to store drop box entries in
     */
    public DropBoxManagerService(Context context, File path) {
        mDropBoxDir = path;

        // Set up intent receivers
        mContext = context;
        mContentResolver = context.getContentResolver();
        context.registerReceiver(mReceiver, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        context.registerReceiver(mReceiver, new IntentFilter(Settings.Gservices.CHANGED_ACTION));

        // The real work gets done lazily in init() -- that way service creation always
        // succeeds, and things like disk problems cause individual method failures.
    }

    /** Unregisters broadcast receivers and any other hooks -- for test instances */
    public void stop() {
        mContext.unregisterReceiver(mReceiver);
    }

    public void add(DropBoxManager.Entry entry) {
        File temp = null;
        OutputStream output = null;
        final String tag = entry.getTag();
        try {
            int flags = entry.getFlags();
            if ((flags & DropBoxManager.IS_EMPTY) != 0) throw new IllegalArgumentException();

            init();
            if (!isTagEnabled(tag)) return;
            long max = trimToFit();
            long lastTrim = System.currentTimeMillis();

            byte[] buffer = new byte[mBlockSize];
            InputStream input = entry.getInputStream();

            // First, accumulate up to one block worth of data in memory before
            // deciding whether to compress the data or not.

            int read = 0;
            while (read < buffer.length) {
                int n = input.read(buffer, read, buffer.length - read);
                if (n <= 0) break;
                read += n;
            }

            // If we have at least one block, compress it -- otherwise, just write
            // the data in uncompressed form.

            temp = new File(mDropBoxDir, "drop" + Thread.currentThread().getId() + ".tmp");
            output = new FileOutputStream(temp);
            if (read == buffer.length && ((flags & DropBoxManager.IS_GZIPPED) == 0)) {
                output = new GZIPOutputStream(output);
                flags = flags | DropBoxManager.IS_GZIPPED;
            }

            do {
                output.write(buffer, 0, read);

                long now = System.currentTimeMillis();
                if (now - lastTrim > 30 * 1000) {
                    max = trimToFit();  // In case data dribbles in slowly
                    lastTrim = now;
                }

                read = input.read(buffer);
                if (read <= 0) {
                    output.close();  // Get a final size measurement
                    output = null;
                } else {
                    output.flush();  // So the size measurement is pseudo-reasonable
                }

                long len = temp.length();
                if (len > max) {
                    Log.w(TAG, "Dropping: " + tag + " (" + temp.length() + " > " + max + " bytes)");
                    temp.delete();
                    temp = null;  // Pass temp = null to createEntry() to leave a tombstone
                    break;
                }
            } while (read > 0);

            createEntry(temp, tag, flags);
            temp = null;
        } catch (IOException e) {
            Log.e(TAG, "Can't write: " + tag, e);
        } finally {
            try { if (output != null) output.close(); } catch (IOException e) {}
            entry.close();
            if (temp != null) temp.delete();
        }
    }

    public boolean isTagEnabled(String tag) {
        return !"disabled".equals(Settings.Gservices.getString(
                mContentResolver, Settings.Gservices.DROPBOX_TAG_PREFIX + tag));
    }

    public synchronized DropBoxManager.Entry getNextEntry(String tag, long millis) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("READ_LOGS permission required");
        }

        try {
            init();
        } catch (IOException e) {
            Log.e(TAG, "Can't init", e);
            return null;
        }

        FileList list = tag == null ? mAllFiles : mFilesByTag.get(tag);
        if (list == null) return null;

        for (EntryFile entry : list.contents.tailSet(new EntryFile(millis + 1))) {
            if (entry.tag == null) continue;
            if ((entry.flags & DropBoxManager.IS_EMPTY) != 0) {
                return new DropBoxManager.Entry(entry.tag, entry.timestampMillis);
            }
            try {
                return new DropBoxManager.Entry(
                        entry.tag, entry.timestampMillis, entry.file, entry.flags);
            } catch (IOException e) {
                Log.e(TAG, "Can't read: " + entry.file, e);
                // Continue to next file
            }
        }

        return null;
    }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: Can't dump DropBoxManagerService");
            return;
        }

        try {
            init();
        } catch (IOException e) {
            pw.println("Can't initialize: " + e);
            Log.e(TAG, "Can't init", e);
            return;
        }

        if (PROFILE_DUMP) Debug.startMethodTracing("/data/trace/dropbox.dump");

        Formatter out = new Formatter();
        boolean doPrint = false, doFile = false;
        ArrayList<String> searchArgs = new ArrayList<String>();
        for (int i = 0; args != null && i < args.length; i++) {
            if (args[i].equals("-p") || args[i].equals("--print")) {
                doPrint = true;
            } else if (args[i].equals("-f") || args[i].equals("--file")) {
                doFile = true;
            } else if (args[i].startsWith("-")) {
                out.format("Unknown argument: %s\n", args[i]);
            } else {
                searchArgs.add(args[i]);
            }
        }

        out.format("Drop box contents: %d entries\n", mAllFiles.contents.size());

        if (!searchArgs.isEmpty()) {
            out.format("Searching for:");
            for (String a : searchArgs) out.format(" %s", a);
            out.format("\n");
        }

        int numFound = 0, numArgs = searchArgs.size();
        Time time = new Time();
        out.format("\n");
        for (EntryFile entry : mAllFiles.contents) {
            time.set(entry.timestampMillis);
            String date = time.format("%Y-%m-%d %H:%M:%S");
            boolean match = true;
            for (int i = 0; i < numArgs && match; i++) {
                String arg = searchArgs.get(i);
                match = (date.contains(arg) || arg.equals(entry.tag));
            }
            if (!match) continue;

            numFound++;
            out.format("%s.%03d %s", date, entry.timestampMillis % 1000,
                     entry.tag == null ? "(no tag)" : entry.tag);
            if (entry.file == null) {
                out.format(" (no file)\n");
                continue;
            } else if ((entry.flags & DropBoxManager.IS_EMPTY) != 0) {
                out.format(" (contents lost)\n");
                continue;
            } else {
                out.format(" (%s%s, %d bytes)\n",
                        (entry.flags & DropBoxManager.IS_GZIPPED) != 0 ? "compressed " : "",
                        (entry.flags & DropBoxManager.IS_TEXT) != 0 ? "text" : "data",
                        entry.file.length());
            }

            if (doFile || (doPrint && (entry.flags & DropBoxManager.IS_TEXT) == 0)) {
                out.format("%s%s\n", (doPrint ? "" : "    "), entry.file.getPath());
            }

            if ((entry.flags & DropBoxManager.IS_TEXT) != 0 && (doPrint || !doFile)) {
                DropBoxManager.Entry dbe = null;
                try {
                    dbe = new DropBoxManager.Entry(
                             entry.tag, entry.timestampMillis, entry.file, entry.flags);

                    if (doPrint) {
                        InputStreamReader r = new InputStreamReader(dbe.getInputStream());
                        char[] buf = new char[4096];
                        boolean newline = false;
                        for (;;) {
                            int n = r.read(buf);
                            if (n <= 0) break;
                            out.format("%s", new String(buf, 0, n));
                            newline = (buf[n - 1] == '\n');
                        }
                        if (!newline) out.format("\n");
                    } else {
                        String text = dbe.getText(70);
                        boolean truncated = (text.length() == 70);
                        out.format("    %s%s\n", text.trim().replace('\n', '/'),
                                truncated ? " ..." : "");
                    }
                } catch (IOException e) {
                    out.format("*** %s\n", e.toString());
                    Log.e(TAG, "Can't read: " + entry.file, e);
                } finally {
                    if (dbe != null) dbe.close();
                }
            }

            if (doPrint) out.format("\n");
        }

        if (numFound == 0) out.format("(No entries found.)\n");

        if (args == null || args.length == 0) {
            if (!doPrint) out.format("\n");
            out.format("Usage: dumpsys dropbox [--print|--file] [YYYY-mm-dd] [HH:MM:SS] [tag]\n");
        }

        pw.write(out.toString());
        if (PROFILE_DUMP) Debug.stopMethodTracing();
    }

    ///////////////////////////////////////////////////////////////////////////

    /** Chronologically sorted list of {@link #EntryFile} */
    private static final class FileList implements Comparable<FileList> {
        public int blocks = 0;
        public final TreeSet<EntryFile> contents = new TreeSet<EntryFile>();

        /** Sorts bigger FileList instances before smaller ones. */
        public final int compareTo(FileList o) {
            if (blocks != o.blocks) return o.blocks - blocks;
            if (this == o) return 0;
            if (hashCode() < o.hashCode()) return -1;
            if (hashCode() > o.hashCode()) return 1;
            return 0;
        }
    }

    /** Metadata describing an on-disk log file. */
    private static final class EntryFile implements Comparable<EntryFile> {
        public final String tag;
        public final long timestampMillis;
        public final int flags;
        public final File file;
        public final int blocks;

        /** Sorts earlier EntryFile instances before later ones. */
        public final int compareTo(EntryFile o) {
            if (timestampMillis < o.timestampMillis) return -1;
            if (timestampMillis > o.timestampMillis) return 1;
            if (file != null && o.file != null) return file.compareTo(o.file);
            if (o.file != null) return -1;
            if (file != null) return 1;
            if (this == o) return 0;
            if (hashCode() < o.hashCode()) return -1;
            if (hashCode() > o.hashCode()) return 1;
            return 0;
        }

        /**
         * Moves an existing temporary file to a new log filename.
         * @param temp file to rename
         * @param dir to store file in
         * @param tag to use for new log file name
         * @param timestampMillis of log entry
         * @param flags for the entry data
         * @param blockSize to use for space accounting
         * @throws IOException if the file can't be moved
         */
        public EntryFile(File temp, File dir, String tag,long timestampMillis,
                         int flags, int blockSize) throws IOException {
            if ((flags & DropBoxManager.IS_EMPTY) != 0) throw new IllegalArgumentException();

            this.tag = tag;
            this.timestampMillis = timestampMillis;
            this.flags = flags;
            this.file = new File(dir, Uri.encode(tag) + "@" + timestampMillis +
                    ((flags & DropBoxManager.IS_TEXT) != 0 ? ".txt" : ".dat") +
                    ((flags & DropBoxManager.IS_GZIPPED) != 0 ? ".gz" : ""));

            if (!temp.renameTo(this.file)) {
                throw new IOException("Can't rename " + temp + " to " + this.file);
            }
            this.blocks = (int) ((this.file.length() + blockSize - 1) / blockSize);
        }

        /**
         * Creates a zero-length tombstone for a file whose contents were lost.
         * @param dir to store file in
         * @param tag to use for new log file name
         * @param timestampMillis of log entry
         * @throws IOException if the file can't be created.
         */
        public EntryFile(File dir, String tag, long timestampMillis) throws IOException {
            this.tag = tag;
            this.timestampMillis = timestampMillis;
            this.flags = DropBoxManager.IS_EMPTY;
            this.file = new File(dir, Uri.encode(tag) + "@" + timestampMillis + ".lost");
            this.blocks = 0;
            new FileOutputStream(this.file).close();
        }

        /**
         * Extracts metadata from an existing on-disk log filename.
         * @param file name of existing log file
         * @param blockSize to use for space accounting
         */
        public EntryFile(File file, int blockSize) {
            this.file = file;
            this.blocks = (int) ((this.file.length() + blockSize - 1) / blockSize);

            String name = file.getName();
            int at = name.lastIndexOf('@');
            if (at < 0) {
                this.tag = null;
                this.timestampMillis = 0;
                this.flags = DropBoxManager.IS_EMPTY;
                return;
            }

            int flags = 0;
            this.tag = Uri.decode(name.substring(0, at));
            if (name.endsWith(".gz")) {
                flags |= DropBoxManager.IS_GZIPPED;
                name = name.substring(0, name.length() - 3);
            }
            if (name.endsWith(".lost")) {
                flags |= DropBoxManager.IS_EMPTY;
                name = name.substring(at + 1, name.length() - 5);
            } else if (name.endsWith(".txt")) {
                flags |= DropBoxManager.IS_TEXT;
                name = name.substring(at + 1, name.length() - 4);
            } else if (name.endsWith(".dat")) {
                name = name.substring(at + 1, name.length() - 4);
            } else {
                this.flags = DropBoxManager.IS_EMPTY;
                this.timestampMillis = 0;
                return;
            }
            this.flags = flags;

            long millis;
            try { millis = Long.valueOf(name); } catch (NumberFormatException e) { millis = 0; }
            this.timestampMillis = millis;
        }

        /**
         * Creates a EntryFile object with only a timestamp for comparison purposes.
         * @param timestampMillis to compare with.
         */
        public EntryFile(long millis) {
            this.tag = null;
            this.timestampMillis = millis;
            this.flags = DropBoxManager.IS_EMPTY;
            this.file = null;
            this.blocks = 0;
        }
    }

    ///////////////////////////////////////////////////////////////////////////

    /** If never run before, scans disk contents to build in-memory tracking data. */
    private synchronized void init() throws IOException {
        if (mStatFs == null) {
            if (!mDropBoxDir.isDirectory() && !mDropBoxDir.mkdirs()) {
                throw new IOException("Can't mkdir: " + mDropBoxDir);
            }
            try {
                mStatFs = new StatFs(mDropBoxDir.getPath());
                mBlockSize = mStatFs.getBlockSize();
            } catch (IllegalArgumentException e) {  // StatFs throws this on error
                throw new IOException("Can't statfs: " + mDropBoxDir);
            }
        }

        if (mAllFiles == null) {
            File[] files = mDropBoxDir.listFiles();
            if (files == null) throw new IOException("Can't list files: " + mDropBoxDir);

            mAllFiles = new FileList();
            mFilesByTag = new HashMap<String, FileList>();

            // Scan pre-existing files.
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) {
                    Log.i(TAG, "Cleaning temp file: " + file);
                    file.delete();
                    continue;
                }

                EntryFile entry = new EntryFile(file, mBlockSize);
                if (entry.tag == null) {
                    Log.w(TAG, "Unrecognized file: " + file);
                    continue;
                } else if (entry.timestampMillis == 0) {
                    Log.w(TAG, "Invalid filename: " + file);
                    file.delete();
                    continue;
                }

                enrollEntry(entry);
            }
        }
    }

    /** Adds a disk log file to in-memory tracking for accounting and enumeration. */
    private synchronized void enrollEntry(EntryFile entry) {
        mAllFiles.contents.add(entry);
        mAllFiles.blocks += entry.blocks;

        // mFilesByTag is used for trimming, so don't list empty files.
        // (Zero-length/lost files are trimmed by date from mAllFiles.)

        if (entry.tag != null && entry.file != null && entry.blocks > 0) {
            FileList tagFiles = mFilesByTag.get(entry.tag);
            if (tagFiles == null) {
                tagFiles = new FileList();
                mFilesByTag.put(entry.tag, tagFiles);
            }
            tagFiles.contents.add(entry);
            tagFiles.blocks += entry.blocks;
        }
    }

    /** Moves a temporary file to a final log filename and enrolls it. */
    private synchronized void createEntry(File temp, String tag, int flags) throws IOException {
        long t = System.currentTimeMillis();

        // Require each entry to have a unique timestamp; if there are entries
        // >10sec in the future (due to clock skew), drag them back to avoid
        // keeping them around forever.

        SortedSet<EntryFile> tail = mAllFiles.contents.tailSet(new EntryFile(t + 10000));
        EntryFile[] future = null;
        if (!tail.isEmpty()) {
            future = tail.toArray(new EntryFile[tail.size()]);
            tail.clear();  // Remove from mAllFiles
        }

        if (!mAllFiles.contents.isEmpty()) {
            t = Math.max(t, mAllFiles.contents.last().timestampMillis + 1);
        }

        if (future != null) {
            for (EntryFile late : future) {
                mAllFiles.blocks -= late.blocks;
                FileList tagFiles = mFilesByTag.get(late.tag);
                if (tagFiles.contents.remove(late)) tagFiles.blocks -= late.blocks;
                if ((late.flags & DropBoxManager.IS_EMPTY) == 0) {
                    enrollEntry(new EntryFile(
                            late.file, mDropBoxDir, late.tag, t++, late.flags, mBlockSize));
                } else {
                    enrollEntry(new EntryFile(mDropBoxDir, late.tag, t++));
                }
            }
        }

        if (temp == null) {
            enrollEntry(new EntryFile(mDropBoxDir, tag, t));
        } else {
            enrollEntry(new EntryFile(temp, mDropBoxDir, tag, t, flags, mBlockSize));
        }
    }

    /**
     * Trims the files on disk to make sure they aren't using too much space.
     * @return the overall quota for storage (in bytes)
     */
    private synchronized long trimToFit() {
        // Expunge aged items (including tombstones marking deleted data).

        int ageSeconds = Settings.Gservices.getInt(mContentResolver,
                Settings.Gservices.DROPBOX_AGE_SECONDS, DEFAULT_AGE_SECONDS);
        long cutoffMillis = System.currentTimeMillis() - ageSeconds * 1000;
        while (!mAllFiles.contents.isEmpty()) {
            EntryFile entry = mAllFiles.contents.first();
            if (entry.timestampMillis > cutoffMillis) break;

            FileList tag = mFilesByTag.get(entry.tag);
            if (tag != null && tag.contents.remove(entry)) tag.blocks -= entry.blocks;
            if (mAllFiles.contents.remove(entry)) mAllFiles.blocks -= entry.blocks;
            if (entry.file != null) entry.file.delete();
        }

        // Compute overall quota (a fraction of available free space) in blocks.
        // The quota changes dynamically based on the amount of free space;
        // that way when lots of data is available we can use it, but we'll get
        // out of the way if storage starts getting tight.

        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis > mCachedQuotaUptimeMillis + QUOTA_RESCAN_MILLIS) {
            int quotaPercent = Settings.Gservices.getInt(mContentResolver,
                    Settings.Gservices.DROPBOX_QUOTA_PERCENT, DEFAULT_QUOTA_PERCENT);
            int reservePercent = Settings.Gservices.getInt(mContentResolver,
                    Settings.Gservices.DROPBOX_RESERVE_PERCENT, DEFAULT_RESERVE_PERCENT);
            int quotaKb = Settings.Gservices.getInt(mContentResolver,
                    Settings.Gservices.DROPBOX_QUOTA_KB, DEFAULT_QUOTA_KB);

            mStatFs.restat(mDropBoxDir.getPath());
            int available = mStatFs.getAvailableBlocks();
            int nonreserved = available - mStatFs.getBlockCount() * reservePercent / 100;
            int maximum = quotaKb * 1024 / mBlockSize;
            mCachedQuotaBlocks = Math.min(maximum, Math.max(0, nonreserved * quotaPercent / 100));
            mCachedQuotaUptimeMillis = uptimeMillis;
        }

        // If we're using too much space, delete old items to make room.
        //
        // We trim each tag independently (this is why we keep per-tag lists).
        // Space is "fairly" shared between tags -- they are all squeezed
        // equally until enough space is reclaimed.
        //
        // A single circular buffer (a la logcat) would be simpler, but this
        // way we can handle fat/bursty data (like 1MB+ bugreports, 300KB+
        // kernel crash dumps, and 100KB+ ANR reports) without swamping small,
        // well-behaved data // streams (event statistics, profile data, etc).
        //
        // Deleted files are replaced with zero-length tombstones to mark what
        // was lost.  Tombstones are expunged by age (see above).

        if (mAllFiles.blocks > mCachedQuotaBlocks) {
            Log.i(TAG, "Usage (" + mAllFiles.blocks + ") > Quota (" + mCachedQuotaBlocks + ")");

            // Find a fair share amount of space to limit each tag
            int unsqueezed = mAllFiles.blocks, squeezed = 0;
            TreeSet<FileList> tags = new TreeSet<FileList>(mFilesByTag.values());
            for (FileList tag : tags) {
                if (squeezed > 0 && tag.blocks <= (mCachedQuotaBlocks - unsqueezed) / squeezed) {
                    break;
                }
                unsqueezed -= tag.blocks;
                squeezed++;
            }
            int tagQuota = (mCachedQuotaBlocks - unsqueezed) / squeezed;

            // Remove old items from each tag until it meets the per-tag quota.
            for (FileList tag : tags) {
                if (mAllFiles.blocks < mCachedQuotaBlocks) break;
                while (tag.blocks > tagQuota && !tag.contents.isEmpty()) {
                    EntryFile entry = tag.contents.first();
                    if (tag.contents.remove(entry)) tag.blocks -= entry.blocks;
                    if (mAllFiles.contents.remove(entry)) mAllFiles.blocks -= entry.blocks;

                    try {
                        if (entry.file != null) entry.file.delete();
                        enrollEntry(new EntryFile(mDropBoxDir, entry.tag, entry.timestampMillis));
                    } catch (IOException e) {
                        Log.e(TAG, "Can't write tombstone file", e);
                    }
                }
            }
        }

        return mCachedQuotaBlocks * mBlockSize;
    }
}
