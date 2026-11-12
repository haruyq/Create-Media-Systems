# Gervill

The synthesizer sources come from Eclipse Temurin 21.0.12.1+1 (`lib/src.zip`, `java.desktop/com/sun/media/sound`, source revision `1c417fbfc2f7`). They are GPL-2.0-only with the Classpath exception. See [LICENSE.txt](LICENSE.txt) and the individual source notices.

The package is relocated to `org.haruyq.gervill` and `SoftSynthesizer` uses the public `os.name` property for OS detection. Create: Media Systems loads its SoundFont explicitly and reads PCM through the synthesizer's stream API.
