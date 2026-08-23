# Matter Light Android app

This is a native Kotlin/Jetpack Compose product-control app for the Matter WS2812 firmware. It currently uses an in-memory demo repository so the interface can be built without private Google credentials. It provides the finished UI boundary for a list of lights, power, brightness, and colour controls.

## Connect it to Matter / Google Home

The `LightRepository` interface in `app/src/main/java/tw/terry/matterlight/LightDevice.kt` is the only layer to replace. A production `GoogleHomeLightRepository` should:

1. Obtain the Google Home APIs Android SDK while signed into Google Home Developers.
2. Configure OAuth and register the Google Home project.
3. Create one `HomeClient`, register `ExtendedColorLightDevice`, `OnOff`, `LevelControl`, and `ColorControl` in its factory registry.
4. Request structure-scoped user permission.
5. Observe devices with `HomeClient.devices()` and map each supported light into `LightDevice`.
6. Send Matter `OnOff`, `LevelControl`, and `ColorControl` commands from the three `set*` methods.

Google requires user consent before an app can access devices in a home. Do not place OAuth client secrets in this repository. Development needs an Android 10+ test phone, a Wi-Fi network, and a compatible Google hub for Matter device control.

## Open in Android Studio

Open the `android` directory in Android Studio Ladybug or newer. The basic UI does not require a Google Home SDK download. Google Home integration is intentionally a follow-up because its SDK access and OAuth configuration are tied to the product owner's Google project.
