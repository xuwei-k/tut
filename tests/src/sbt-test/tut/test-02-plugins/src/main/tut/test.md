Should get a warning

```tut
class Functor[F[_]]
```

Should NOT get an error

```tut:nofail
object X extends Functor[Either[String, ?]]
```

```tut
val a = "a"
```

```tut:fail:reset
a
```

```tut
class Functor[F[_]]
```

```tut
object X extends Functor[Either[String, ?]]
```
