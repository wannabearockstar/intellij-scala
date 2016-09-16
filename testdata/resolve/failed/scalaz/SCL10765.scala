trait Phantom

val tagged = scalaz.Tag[Int, Phantom](5)
tagged.< ref > toString