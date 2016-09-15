(ns puppetlabs.stockpile.queue-test
  (:require [puppetlabs.stockpile.queue :as stock]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer :all])
  (:import
   [org.apache.commons.lang3 RandomStringUtils]
   [java.io ByteArrayInputStream File IOException]
   [java.nio.file Files NoSuchFileException OpenOption Path StandardOpenOption]
   [java.nio.file.attribute FileAttribute]
   [puppetlabs.stockpile.queue MetaEntry]))

(defn relativize-file [wrt-path f]
  (.relativize wrt-path (.toPath f)))

(defn relative-pathstr-seq [parent]
  (map #(str (relativize-file parent %))
       (file-seq (.toFile parent))))

(def small-test-fs
  (if-let [v (System/getenv "STOCKPILE_TINY_TEST_FS")]
    (stock/path-get v)
    (binding [*out* *err*]
      (println "STOCKPILE_TINY_TEST_FS not defined; skipping related tests")
      false)))

(defn random-path-segment [n]
  (loop [s (RandomStringUtils/random n)]
    (if (and (= -1 (.indexOf s java.io.File/separator))
             (= -1 (.indexOf s (int \u0000))))
      s
      (recur (RandomStringUtils/random n)))))

 (defn rm-r [pathstr]
   ;; Life's too short...
   (let [rm (shell/sh "rm" "-r" pathstr)]
     (when-not (zero? (:exit rm))
       (throw (-> "'rm -r %s' failed: %s"
                  (format (pr-str pathstr) (pr-str rm))
                  Exception.)))))

(defn call-with-temp-dir-path
  [f]
  (let [tempdir (Files/createTempDirectory (.toPath (File. "target"))
                                           "stockpile-test-"
                                           (into-array FileAttribute []))
        tempdirstr (str (.toAbsolutePath tempdir))
        result (try
                 (f (.toAbsolutePath tempdir))
                 (catch Exception ex
                   (binding [*out* *err*]
                     (println "Error: leaving temp dir" tempdirstr))
                   (throw ex)))]
    (rm-r tempdirstr)
    result))

(defn entry-path [q entry]
  (#'stock/queue-entry-path q (stock/entry-id entry) (stock/entry-meta entry)))

(defn slurp-entry [q entry]
  (slurp (stock/stream q entry)))

(defn store-str
  ([q s]
   (let [ent (stock/store q (-> s (.getBytes "UTF-8") ByteArrayInputStream.))
         id (stock/entry-id ent)]
     (is (integer? ent))
     (is (integer? id))
     (is (not (stock/entry-meta ent)))
     ent))
  ([q s metadata]
   (let [ent (stock/store q
                          (-> s (.getBytes "UTF-8") ByteArrayInputStream.)
                          metadata)
         id (stock/entry-id ent)
         meta (stock/entry-meta ent)]
     (is (integer? id))
     (if metadata
       (do
         (is (instance? MetaEntry ent))
         (is (= metadata (stock/entry-meta ent))))
       (is (not (stock/entry-meta ent))))
     ent)))

(defn purge-queue [qdir]
  (let [q (stock/open qdir)
        entries (stock/reduce q conj ())]
    (doseq [entry entries]
      (stock/discard entry))))

(deftest bad-entries
  (is (thrown? IllegalArgumentException (stock/entry "foo")))
  (is (thrown? IllegalArgumentException (stock/entry "foo" 1)))
  (is (thrown? IllegalArgumentException (stock/entry 1 2))))

(deftest entry-ids
  (call-with-temp-dir-path
   (fn [tmpdir]
     ;; Expectations specific to the current implementation
     (let [q (stock/create (.toFile (.resolve tmpdir "queue")))]
       (is (zero? (stock/next-likely-id q)))
       (let [e (store-str q "first")]
         (is (zero? (stock/entry-id e)))
         (is (= 1 (stock/next-likely-id q)))
         (let [e (store-str q "second")]
           (is (= 1 (stock/entry-id e)))
           (is (= 2 (stock/next-likely-id q)))))))))

(deftest basics
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [q (stock/create (.toFile (.resolve tmpdir "queue")))]
       (let [entry-1 (store-str q "foo")
             entry-2 (store-str q "bar" "*so* meta")
             id-1 (stock/entry-id entry-1)
             id-2 (stock/entry-id entry-2)]
         (is (< id-1 id-2))
         (is (> id-2 id-1))
         (is (= "foo" (slurp-entry q entry-1)))
         (is (= "bar" (slurp-entry q entry-2)))

         (stock/discard q entry-1)
         (is (= "bar" (slurp-entry q entry-2)))
         (try
           (slurp-entry q entry-1)
           (catch Exception ex
             (= {:entry entry-1 :source (entry-path q entry-1)}
                (ex-data ex))))))
     (is (= #{"" "queue" "queue/q" "queue/q/1-*so* meta" "queue/stockpile"}
            (set (relative-pathstr-seq tmpdir)))))))

(deftest basic-persistence
  ;; Some of the validation is handled implicitly by store-str
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           reduction-0 (stock/reduce (stock/create qdir)
                                     #(throw (Exception. "unexpected")) :empty)
           ent-1 (-> (stock/open qdir) (store-str "foo"))
           ent-1-id (stock/entry-id ent-1)
           reduction-1 (stock/reduce (stock/open qdir) conj #{})]

       (is (= :empty reduction-0))

       ;; Check first reduction (should be one element)
       (is (= #{ent-1} reduction-1))
       (is (= #{(stock/entry ent-1-id nil)} reduction-1))
       (let [ent (first reduction-1)]
         (is (= ent-1-id (stock/entry-id ent)))
         (is (not (stock/entry-meta ent))))

       (is (= "foo" (slurp-entry (stock/open qdir) ent-1)))

       (let [ent-2 (-> (stock/open qdir) (store-str "bar" "meta bar"))
             ent-2-id (stock/entry-id ent-2)
             reduction-2 (stock/reduce (stock/open qdir) conj #{})]

         ;; Check second reduction (should be two elements)
         (is (= #{ent-1 ent-2} reduction-2))
         (is (= #{(stock/entry ent-1-id nil)
                  (stock/entry ent-2-id "meta bar")}
                reduction-2))
         (let [ent (get reduction-2 ent-2)]
           (is (= ent-2-id (stock/entry-id ent)))
           (is (= "meta bar" (stock/entry-meta ent))))

         (let [q (stock/open qdir)]
           (is (= "foo" (slurp-entry q ent-1)))
           (is (= "bar" (slurp-entry q ent-2))))))

     (is (= #{"" "queue" "queue/q"
              "queue/q/1" "queue/q/2-meta bar"
              "queue/stockpile"}
            (set (relative-pathstr-seq tmpdir)))))))

(deftest entry-manipulation
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           inputs (for [i (range 10)] [(str i) (str "meta-" i)])
           entries (for [[data metadata] inputs]
                     (store-str q data metadata))]
       (doall
        (map (fn [input entry]
               (let [id (stock/entry-id entry)
                     metadata (stock/entry-meta entry)
                     reconstituted (stock/entry id metadata)]
                 (is (= entry reconstituted))
                 (is (= (first input)
                        (slurp-entry q reconstituted)))))
             inputs
             entries))))))

(deftest cleanup-failure-after-open-failure
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           delete-failed (Exception. "delete")
           rename-failed (Exception. "rename")
           ex (try
                (with-redefs [stock/delete-if-exists (fn [& args]
                                                       (throw delete-failed))
                              stock/rename-durably (fn [& args]
                                                     (throw rename-failed))]
                  (stock/create qdir))
                (catch Exception ex
                  ex))
           data (ex-data ex)]
       (is (= ::stock/path-cleanup-failure-after-error (:kind data)))
       (is (= delete-failed (:exception data)))
       (is (instance? Path (:path data)))
       (is (.exists (.toFile (:path data))))
       (is (= rename-failed (.getCause ex)))))))

(deftest cleanup-failure-after-store-failure
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           delete-failed (Exception. "delete")
           write-failed (Exception. "write")
           q (stock/create qdir)
           ex (try
                (with-redefs [stock/delete-if-exists (fn [& args]
                                                       (throw delete-failed))
                              stock/write-stream (fn [& args]
                                                   (throw write-failed))]
                  (store-str q "first"))
                (catch Exception ex
                  ex))
           data (ex-data ex)]
       (is (= ::stock/path-cleanup-failure-after-error (:kind data)))
       (is (= delete-failed (:exception data)))
       (is (instance? Path (:path data)))
       (is (.exists (.toFile (:path data))))
       (is (= write-failed (.getCause ex)))))))

(deftest commit-failure-during-store
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           rename-failed (Exception. "rename")
           q (stock/create qdir)
           ex (try
                (with-redefs [stock/rename-durably (fn [& args]
                                                     (throw rename-failed))]
                  (store-str q "first"))
                (catch Exception ex
                  ex))
           data (ex-data ex)]
       (is (= ::stock/unable-to-commit (:kind data)))
       (is (instance? Path (:stream-data data)))
       (is (= "first" (slurp (.toFile (:stream-data data)))))
       (is (= rename-failed (.getCause ex)))))))

(deftest meta-encoding-round-trip
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           batch-size 100]
       (dotimes [i batch-size]
         ;; We need to use a very short length here to avoid falling
         ;; afoul of path length limits since 8 random unicode
         ;; chars could expand to say 36 encoded bytes.
         (let [metadata (random-path-segment (rand-int 8))]
           (store-str q metadata metadata)))))))

(deftest existing-tmp-removal
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           garbage (File. qdir "q/tmp-garbage")]
       (stock/create qdir)
       (io/copy "foo" (File. qdir "q/tmp-garbage"))
       (let [q (stock/open qdir)
             entries (stock/reduce q conj ())]
         (is (= [] entries))
         (is (not (.exists garbage))))))))

(defn test-discard-entry-to [destination tmpdir q-name]
  (let [qdir (.resolve tmpdir q-name)
        newq (stock/create qdir)]
    (let [entry (store-str newq "foo")
          q (stock/open qdir)
          read-entries (stock/reduce q conj ())]
      (is (= [entry] read-entries))
      (stock/discard q entry destination)
      (is (= "foo" (String. (Files/readAllBytes destination) "UTF-8"))))))

(deftest discard-to-destination
  (call-with-temp-dir-path
   (fn [tmpdir]
     (test-discard-entry-to (.resolve tmpdir "discarded") tmpdir "q1")
     (when small-test-fs
       (let [dest (Files/createTempFile small-test-fs "discarded-" ""
                                        (into-array FileAttribute []))]
         (try
           (test-discard-entry-to dest tmpdir "q2")
           (finally
             (Files/delete dest))))))))

(defn fill-filesystem [path]
  "Returns truish value if the filesystem containing path is likely full."
  (let [append StandardOpenOption/APPEND
        buf (byte-array (* 64 1024) (byte \?))
        write-chunks (fn [write-chunk open-opts]
                       (with-open [out (Files/newOutputStream
                                        path
                                        (into-array OpenOption open-opts))]
                         (try
                           (while true (write-chunk out))
                           (catch IOException ex true))))]
    ;; Write smaller and smaller chunks; finish up with single bytes.
    (write-chunks #(.write % buf 0 (* 64 1024)) [])
    (write-chunks #(.write % buf 0 1024) [append])
    (write-chunks #(.write % (int \?)) [append])))

(deftest full-filesystem-behavior
  (when small-test-fs
    (let [qdir (.resolve small-test-fs "full-q")
          nopedir (.resolve small-test-fs "no-q")
          q (stock/create qdir)
          balloon (Files/createTempFile small-test-fs "balloon-" ""
                                        (into-array FileAttribute []))]
      (try
        (let [firehose (future (fill-filesystem balloon))
              result (deref firehose (* 30 1000) nil)]
          (is result)
          (if-not result
            (future-cancel firehose)
            (let [free (.getUsableSpace (Files/getFileStore balloon))]
              (is (= 0 free))
              (when (zero? free)
                (is (thrown? IOException (stock/create nopedir)))
                (is (thrown? IOException (store-str q "foo")))))))
        (finally
          (Files/delete balloon)))
      (let [q (stock/open qdir)
            read-entries (stock/reduce q conj ())]
        (is (= [] read-entries))))))

(def billion 1000000000)

(deftest uncontended-performance
  ;; This also tests random metadata round trips
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))]
       (doall
        (for [make-meta [nil #(random-path-segment 4)]
              batch-size [100 1000]
              i (range 3)]
          (do
            (let [q (stock/create qdir)
                  ;; Uncontended enqueue
                  start (System/nanoTime)
                  items (doall (for [i (range batch-size)]
                                 (let [m (and make-meta (make-meta))
                                       ent (store-str q (str i) m)]
                                   (when m
                                     (is (= m (stock/entry-meta ent))))
                                   [m ent])))
                  stop (System/nanoTime)
                  _ (binding [*out* *err*]
                      (printf "Enqueued %d tiny messages %s metadata at %.2f/s\n"
                              batch-size
                              (if make-meta "with" "without")
                              (double (/ batch-size (/ (- stop start) billion))))
                      (flush))
                  ;; Uncontended streams
                  start (System/nanoTime)
                  _ (is (= (set (map str (range batch-size)))
                           (set (for [[metadata entry] items]
                                  (slurp-entry q entry)))))
                  stop (System/nanoTime)
                  _ (binding [*out* *err*]
                      (printf "Streamed %d tiny messages %s metadata at %.2f/s\n"
                              batch-size
                              (if make-meta "with" "without")
                              (double (/ batch-size (/ (- stop start) billion))))
                      (flush))
                  ;; Uncontended discard
                  start (System/nanoTime)
                  _ (doseq [[metadata entry] items]
                      (stock/discard q entry))
                  stop (System/nanoTime)
                  _ (binding [*out* *err*]
                      (printf "Discarded %d tiny messages %s metadata at %.2f/s\n"
                              batch-size
                              (if make-meta "with" "without")
                              (double (/ batch-size (/ (- stop start) billion))))
                      (flush))]
              (is (= #{"" "q" "stockpile"}
                     (set (relative-pathstr-seq (.toPath qdir))))))
            (rm-r (.getAbsolutePath qdir)))))))))

(deftest contending-enqueue-dequeue-performance
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           batch-size 2000
           start (System/nanoTime)
           entries (seque (int (max 100 (/ batch-size 10)))
                          (for [i (range batch-size)]
                            (store-str q (str i))))]
       (doall
        (map (fn [i entry]
               (is (= (str i) (slurp-entry q entry)))
               (stock/discard q entry)
               (try
                 (slurp-entry q entry)
                 (catch Exception ex
                   (= {:entry entry :source (entry-path q entry)}
                      (ex-data ex)))))
             (range batch-size)
             entries))
       (binding [*out* *err*]
         (printf "Enqueued and dequeued %d tiny messages in parallel at %.2f/s\n"
                 batch-size
                 (double (/ batch-size
                            (/ (- (System/nanoTime) start)
                               billion))))
         (flush))
       (is (= #{"" "q" "stockpile"}
              (set (relative-pathstr-seq (.toPath qdir)))))))))

(deftest simple-race
  (call-with-temp-dir-path
   (fn [tmpdir]
     (let [batch-size 300
           qdir (.toFile (.resolve tmpdir "queue"))
           q (stock/create qdir)
           state (atom {:entries () :victim nil})
           finished? (atom false)
           writer (future
                    (dotimes [i batch-size]
                      (swap! state update :entries conj
                             [i (store-str q (str i) (str "meta-" i))])))
           reader (future
                    (while (not @finished?)
                      (let [{:keys [victim]} (swap! state
                                                    (fn [{[v & r] :entries}]
                                                      {:entries r
                                                       :victim v}))
                            [val entry] victim]
                        (when victim
                          (is (= (str val) (slurp-entry q entry)))
                          (swap! state update :entries conj victim)))))
           discarder (future
                       (loop [i 0]
                         (when (< i batch-size)
                           (let [{:keys [victim]} (swap! state
                                                         (fn [{[v & r] :entries}]
                                                           {:entries r
                                                            :victim v}))
                                 [val entry] victim]
                             (if entry
                               (do
                                 (stock/discard q entry)
                                 (recur (inc i)))
                               (recur i))))))]
       @writer @discarder
       (reset! finished? true)
       @reader
       (is (= #{"" "q" "stockpile"}
              (set (relative-pathstr-seq (.toPath qdir)))))))))
