Should get a warning

```scala
scala> class Functor[F[_]]
warning: there was one feature warning; re-run with -feature for details
defined class Functor
```

Should NOT get an error

```scala
scala> object X extends Functor[Either[String, ?]]
defined object X
```

```scala
scala> val a = "a"
a: String = a
```

```scala
scala> a
<console>:9: error: not found: value a
              a
              ^
```

```scala
scala> class Functor[F[_]]
warning: there was one feature warning; re-run with -feature for details
defined class Functor
```

```scala
scala> object X extends Functor[Either[String, ?]]
defined object X
```
