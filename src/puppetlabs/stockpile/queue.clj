(ns puppetlabs.stockpile.queue
  (:refer-clojure :exclude [reduce])
  (:import
   [clojure.lang BigInt]
   [java.io File FileOutputStream ByteArrayInputStream]
   [java.nio.file AtomicMoveNotSupportedException DirectoryStream
    FileSystemException Path Paths]
   [java.nio.channels FileChannel]
   [java.nio.file FileAlreadyExistsException Files OpenOption StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.util.concurrent.atomic AtomicLong]))

;; Queue structure:
;;   - qdir/stockpile
;;   - qdir/q/INTEGER                    # message
;;   - qdir/q/INTEGER-ENCODED_METADATA   # message
;;   - qdir/q/tmp-BLARG                  # pending message

(defn- basename [^Path path]
  (.getName path (dec (.getNameCount path))))

(defn ^Path path-get [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))

(defn- parse-integer [x]
  (try
    (Long/parseLong x)
    (catch NumberFormatException ex
      nil)))

(defprotocol AsPath
  (as-path ^Path [x]))

(extend-protocol AsPath
  Path
  (as-path [x] x)
  String
  (as-path [x] (path-get x))
  File
  (as-path [x] (.toPath x)))

(defprotocol Entry
  (entry-id [entry])
  (entry-meta [entry]))

(defrecord MetaEntry [id metadata]
  Entry
  (entry-id [this] id)
  (entry-meta [this] metadata))

(extend-protocol Entry
  Long
  (entry-id [this] this)
  (entry-meta [this] nil))

(defn entry [id metadata]
  (let [id (if (integer? id)
             (long id)
             (throw
              (IllegalArgumentException. (str "id is not an integer: " id))))]
    (cond
      (nil? metadata) id

      (not (string? metadata))
      (throw
       (IllegalArgumentException. (str "metadata is not a string: " metadata)))

      :else (->MetaEntry id metadata))))

(defn- create-tmp-file [parent]
  (Files/createTempFile (as-path parent) "tmp-" ""
                        (into-array FileAttribute [])))

(defn fsync [x metadata?]
  (with-open [fc (FileChannel/open (as-path x)
                                   (into-array OpenOption []))]
    (.force fc metadata?)))

(def ^:private copt-atomic StandardCopyOption/ATOMIC_MOVE)
(def ^:private copt-replace StandardCopyOption/REPLACE_EXISTING)
(def ^:private copts-type (class (into-array StandardCopyOption [])))

(defn ^copts-type copts [opts]
  (into-array StandardCopyOption opts))

(defn- atomic-move [src dest]
  (Files/move (as-path src) (as-path dest)
              (copts [copt-atomic])))

(defn- rename-durably
  "If possible, atomically renames src to dest (each of which may be a
  File, Path, or String).  If dest already exists, on some platforms
  the replacement will succeed, and on others it will throw an
  IOException.  The rename may also fail with
  AtomicMoveNotSupportedException (perhaps if src and dest are on
  different filesystems).  See java.nio.file.Files/move for additional
  information.  Fsyncs the dest parent directory to make the final
  rename durable unless sync-parent? is false (presumably the caller
  will ensure the sync)."
  [src dest sync-parent?]
  (atomic-move src dest)
  (when sync-parent?
    (fsync (.getParent (as-path dest)) true)))

(defn- durably-establish
  "Calls (write-content temp-path) and durably stores the resulting
  temp-file contents at path (a File, Path, or String).  Fsyncs the
  parent directory of dest to make the final rename durable unless
  sync-parent? is false (presumably the caller will ensure the sync).
  See rename-durably for additional information.  As compared
  to (store q stream ...), the write function here allows the caller
  more control over what happens when something goes wrong (say they
  know how they might free up space in the filesystem if write fails).
  Currently when an exception is thrown, it's possible (but unlikely)
  that this function may have left a temp file in the parent directory
  of path."
  [path write-content sync-parent?]
  (let [parent (.getParent (as-path path))
        tmp (create-tmp-file parent)]
    (try
      (write-content tmp)
      (fsync tmp false)
      (rename-durably tmp path sync-parent?)
      (catch Exception ex
        ;; This approach will be revisited/revised after we discuss
        ;; the alternatives a bit further.
        (try
          (Files/deleteIfExists tmp)
          (catch Exception ex
            true))
        (throw ex)))))

(defn- qpath ^Path [{:keys [^Path directory] :as q}]
  (.resolve directory "q"))

(defn- queue-entry-path
  [q id metadata]
  (let [^Path parent (qpath q)
        ^String entry-name (apply str id (when metadata ["-" metadata]))]
    (.resolve parent entry-name)))

(defn- entry-path
  [q entry]
  (queue-entry-path q (entry-id entry) (entry-meta entry)))

(defn- filename->entry
  "Returns an entry if name can be parsed as such, i.e. either as
  an integer or integer-metadata, nil otherwise."
  [^String name]
  (let [dash (.indexOf name (int \-))]
    (if (= -1 dash)
      (parse-integer name)
      ;; Perhaps it has metadata
      (when-let [id (parse-integer (subs name 0 dash))]
        (->MetaEntry id (subs name (inc dash)))))))

(defrecord Stockpile [directory next-likely-id])

(defn- reduce-paths
  [f val ^DirectoryStream dirstream]
  (with-open [_ dirstream]
    (clojure.core/reduce f val (-> dirstream .iterator iterator-seq))))

(defn- plausible-prefix?
  [s]
  (-> #"^[0-9](?:-.)?+" (.matcher s) .find))


;;; Stable, public interface

(defn next-likely-id
  "Returns a likely id for the next message stored in the q.  No
  subsequent entry ids will be less than this value."
  [{^AtomicLong next :next-likely-id :as q}]
  (.get next))

(defn create
  "Creates a new queue in directory, which must not exist, and returns
  the queue."
  [directory]
  (let [top (as-path directory)
        q (.resolve top "q")]
    (Files/createDirectory top (into-array FileAttribute []))
    (Files/createDirectory q (into-array FileAttribute []))
    ;; This sentinel is last - indicates the queue is *ready*
    (durably-establish (.resolve top "stockpile")
                       ;; Assumes that copy won't use a
                       ;; BufferedWriter (FilterOutputStream) in this
                       ;; case (otherwise it'll be broken with at
                       ;; least openjdk-7.
                       (fn [^Path f]
                          (with-open [out (FileOutputStream. (.toFile f))]
                            (.write out (.getBytes "0 stockpile" "UTF-8"))))
                       false)
    (fsync top true)
    (->Stockpile top (AtomicLong. 0))))

(defn open
  "Opens the queue in directory, and returns it.  Expects only
  stockpile created files in the directory, and currently deletes any
  existing file in the queue whose name starts with \"tmp-\"."
  [directory]
  (let [top (as-path directory)
        q (.resolve top "q")]
    (let [info-file (.resolve top "stockpile")
          info (String. (Files/readAllBytes info-file) "UTF-8")]
      (when-not (= "0 stockpile" info)
        (throw (IllegalStateException.
                (format "Invalid queue token %s found in %s"
                        (pr-str info)
                        (pr-str info-file))))))
    (let [max-id (reduce-paths (fn [result ^Path p]
                                 (let [name (str (basename p))]
                                   (cond
                                     (.startsWith name "tmp-")
                                     (do (Files/deleteIfExists p) result)

                                     (plausible-prefix? name)
                                     (max result (-> name
                                                     filename->entry
                                                     entry-id))
                                     
                                     :else
                                     result)))
                               0
                               (Files/newDirectoryStream q))]
      (->Stockpile top (AtomicLong. (inc max-id))))))

(defn reduce
  "Calls (f reduction entry) for each existing entry as-per reduce,
  with val as the initial reduction, and returns the result.  The
  ordering of the calls is unspecified, as is the effect of concurrent
  discards.  The reduction may be escaped by throwing a unique
  exception (cf. slingshot).  For example: (reduce \"foo\" conj [])."
  [q f val]
  (reduce-paths (fn [result ^Path p]
                  (let [name (-> p basename str)]
                    (if-not (plausible-prefix? name)
                      result
                      (f result (filename->entry name)))))
                val
                (Files/newDirectoryStream (qpath q))))

(defn store
  "Atomically and durably enqueues the content of stream, and returns
  an entry that can be used to refer to the content later.  An ex-info
  exception of {:kind ::unable-to-commit :stream-data path} may be
  thrown if store was able to read the data from the stream, but
  unable to make it durable.  If any other exception is thrown, the
  state of the stream is unknown.  The :stream-data value will be a
  path to a file containing all of the data that was in the stream.
  Among other things, it's possible that ::unable-to-commit indicates
  the metadata was incompatible with the underlying filesystem (it was
  too long, couldn't be encoded, etc.).  That's because the current
  implementation records the metadata in a file name corresponding to
  the entry, and may use up to 20 (Unicode Basic Latin block)
  characters of that file name for internal purposes.  The remainder
  of the filename is available for the metadata, but the maximum
  length of that remainder depends on the platform and target
  filesystem.  Many common filesystems now allow a file name to be up
  to 255 characters or bytes, and at least on Linux, the JVM converts
  the Unicode string path to a filesystem path using an encoding that
  depends on the locale, often choosing UTF-8.  So assuming a UTF-8
  encoding and a 255 byte maximum path length (e.g. ext4), after
  subtracting the 20 (UTF-8 encoded Basic Latin block) bytes reserved
  for internal use, there may be up to 235 bytes available for the
  metadata.  Of course how many Unicode characters that will allow
  depends on their size when converted to UTF-8."
  ([q stream] (store q stream nil))
  ([q ^ByteArrayInputStream stream metadata]
   (let [^AtomicLong next (:next-likely-id q)
         qd (qpath q)]
     (let [^Path tmp-dest (create-tmp-file qd)]
       ;; It might be possible to optimize some cases with
       ;; transferFrom/transferTo eventually.
       (try
         (Files/copy stream tmp-dest (copts [copt-replace]))
         (catch Exception ex
           ;; This approach will be revisited/revised after we discuss
           ;; the alternatives a bit further.
           (try
             (Files/delete tmp-dest)
             (catch Exception ex
               true))
           (throw ex)))
       (try
         (fsync tmp-dest false)
         (loop []
           (let [id (.getAndIncrement next)
                 target (queue-entry-path q id metadata)
                 ;; Can't recur from catch
                 moved? (try
                          (rename-durably tmp-dest target true)
                          true
                          (catch FileAlreadyExistsException ex
                            false))]
             (if moved?
               (entry id metadata)
               (recur))))
         (catch Exception ex
           (throw (ex-info "unable to commit" {:kind ::unable-to-commit
                                               :stream-data tmp-dest}
                           ex))))))))

(defn stream
  "Returns an unbuffered stream of the entry's data."
  [q entry]
  (Files/newInputStream (entry-path q entry) (into-array OpenOption [])))

(defn discard
  "Atomically and durably discards the entry (returned by store) from
  the queue.  The discarded data will be placed at the destination
  path (durably if possible), when one is provided.  This should be
  much more efficient, and likely safer if the destination is at least
  on the same filesystem as the queue.  The results of calling this
  more than once for a given entry are undefined."
  ;; Not entirely certain the queue parent dir syncs are necessary *if*
  ;; everyone guarantees that you either see the file or not, and if
  ;; we're OK with the possibility of spurious redelivery.
  ([q entry]
   (Files/deleteIfExists (entry-path q entry))
   (fsync (qpath q) true))
  ([q entry destination]
   (let [^Path src (entry-path q entry)
         ^Path destination (as-path destination)
         moved? (try
                  (Files/move src destination (copts [copt-atomic]))
                  true
                  (catch UnsupportedOperationException ex
                    false)
                  (catch AtomicMoveNotSupportedException ex
                    false))]
     (when-not moved?
       (Files/copy src destination (copts [copt-replace]))
       (Files/delete src))
     (fsync (.getParent destination) true)
     (fsync (qpath q) true))))
