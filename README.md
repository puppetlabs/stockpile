# stockpile [![Clojars Project](https://img.shields.io/clojars/v/stockpile.svg)](https://clojars.org/stockpile) [![Build Status](https://travis-ci.org/puppetlabs/stockpile.svg?branch=master)](https://travis-ci.org/puppetlabs/stockpile)

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
file and its parent directory makes the file durable.  At last look,
[that did not include OS X](https://bugs.openjdk.java.net/browse/JDK-8080589).

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
additional tests can only be run if `STOCKPILE\_TINY\_TEST\_FS` is set
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

## License

Copyright © 2016 Puppet Labs Inc

Distributed under the Apache License Version 2.0.  See ./LICENSE for
details.
