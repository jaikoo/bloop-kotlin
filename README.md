# BloopClient (Kotlin)

Kotlin/JVM error tracking SDK for [Bloop](https://github.com/jaikoo/eewwror) — self-hosted error tracking.

## Install (Gradle)

```kotlin
implementation("com.bloop:bloop-client:0.1.0")
```

## Usage

```kotlin
val bloop = BloopClient("https://errors.myapp.com", "bloop_abc123...")

bloop.send(JSONObject().apply {
    put("timestamp", System.currentTimeMillis() / 1000)
    put("source", "android")
    put("environment", "production")
    put("release", "3.0.1")
    put("error_type", "IllegalStateException")
    put("message", "Fragment not attached to activity")
    put("screen", "ProfileFragment")
})
```

## Features

- **javax.crypto HMAC** — Signed requests via HMAC-SHA256
- **OkHttp** — Async fire-and-forget with callback
- **Zero config** — Just endpoint + project key
- **JVM compatible** — Works on Android and server-side Kotlin

## License

MIT
