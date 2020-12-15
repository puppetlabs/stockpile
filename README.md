# stockpile [![Clojars Project](https://img.shields.io/clojars/v/puppetlabs/stockpile.svg)](https://clojars.org/puppetlabs/stockpile) [![Build Status](https://travis-ci.org/puppetlabs/stockpile.svg?branch=master)](https://travis-ci.org/puppetlabs/stockpile)

A simple, durable Clojure queueing library.  While this is believed to
be reasonably solid, it is still relatively new, and the API or
behavior may change without warning.

Stockpile supports the durable storage and retrieval of data.  After
storage, stockpile returns an `entry` that can be used to access the
data later, and when no longer needed, the data can be atomically
`discard`ed.

Stockpile is explicitly designed to keep minimal state outside the
filesystem.  After opening a queue, you can call `reduce` to traverse
the existing entries, but stockpile itself does not retain information
about the entries.  You must preserve any that you might want to
access later.

The ordering of any two entries can be compared (by `id`), but that
ordering is not guaranteed to be exact, only some approximation of
their relative insertion order.

A metadata string can be provided for each item stored, but the length
of that string may be limited by the underlying filesystem.  (The
string is currently encoded into the queue item's pathname so that it
can be retrieved without having to open or read the file itself).  The
filesystem might also alter the metadata in other ways, for example if
it does not preserve case.  The path that's ultimately specified to
the filesystem by the JVM may be affected by the locale, and on Linux
with common filesystems, for example, often produces UTF-8 paths.  See
the queue `store` docstring for further information.

Stockpile is intended to work correctly on any filesystem where rename
(ATOMIC\_MOVE) works correctly, and where calling fsync/fdatasync on a
file and its parent directory makes the file durable.  In the past
(before JDK 9), [that did not include OS X](https://bugs.openjdk.java.net/browse/JDK-8080589).

And there's apparently a [controversy](http://mail.openjdk.java.net/pipermail/nio-dev/2015-May/003140.html)
about whether to continue to support the undocumented method stockpile
uses to sync the parent directory, or to
[provide some other documented mechanism](https://bugs.java.com/bugdatabase/view_bug.do?bug_id=8080235).

Unless the items being inserted into the queue are large enough for
the sequential transfer rate to dominate, the insertion rate is likely
to be limited by the maximum "fsync/fdatasync rate" of the underlying
filesystem.

The current implementation tracks the queue ids using an AtomicLong,
and while that counter could overflow, even at 100,000 stores per
second, it should require about 290 million years.

Stockpile's behavior given unexpected files inside its directory is
undefined.

## Usage

See queue.clj and queue_test.clj for the API documentation and sample
usage.

## Testing

As expected, "lein test" will run the test suite, but there are some
additional tests can only be run if `STOCKPILE_TINY_TEST_FS` is set
to a directory that resides on an otherwise quiet filesystem with less
than 10MB of free space, that is not the current filesystem:

    STOCKPILE_TINY_TEST_FS=~/tmp/tiny lein test

During the tests stockpile may repeatedly fill that filesystem.

You can also run the tests via `./run-test`, and if you're on Linux
and root, it will automatically set up, use, and tear down a suitable
tiny loopback filesystem.  Alternately, if you have sudo access to
root, you can invoke `./run-test --use-sudo` to do the same.  Though
invoking `./run-test` outside of a "throwaway" virtual machine is not
recommended right now.

## Implementation notes

The current implementation follows the the traditional POSIX-oriented
approach of storing each entry in its own file, with a name that's
guaranteed to be unique, and where durability is provided by this
sequence of operations:

  - Write the entry data to a temp file,
  - fdatasync() the temp file,
  - rename() the temp file to the final name,
  - and fsync() the parent directory.

Or rather, stockpile calls JVM functions that are believed to provide
that behavior or an equivalent result.  The parent directory fsync()
is required in order to ensure that the destination file name is also
durable.

Given this approach, the "fsync rate" of the underlying filesystem
will constrain the entry storage rate for a given queue, and so
batching multiple items into a single entry (when feasible) may be an
effective way to increase performance.

### An overview of relevant concepts:

  - http://blog.httrack.com/blog/2013/11/15/everything-you-always-wanted-to-know-about-fsync/

### Related JVM methods and documentation:

  - https://docs.oracle.com/javase/7/docs/api/java/nio/channels/FileChannel.html#force(boolean)
  - https://docs.oracle.com/javase/7/docs/api/java/nio/file/Files.html#move(java.nio.file.Path,%20java.nio.file.Path,%20java.nio.file.CopyOption...)

### Supporting POSIX functions:

  - http://pubs.opengroup.org/onlinepubs/9699919799/functions/fsync.html
  - http://pubs.opengroup.org/onlinepubs/9699919799/functions/fdatasync.html
  - http://pubs.opengroup.org/onlinepubs/9699919799/functions/rename.html

## License

Copyright © 2016 Puppet Labs Inc

Distributed under the Apache License Version 2.0.  See ./LICENSE for
details.
