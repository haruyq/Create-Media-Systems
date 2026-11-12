# PX-S1100WE model

The generated model, textures and layout are committed under `src/main/resources/assets/create_media_systems`. To regenerate them from the model source, install Pillow and run these commands at the repository root.

```sh
python tools/px_s1100/gen_px_s1100.py --namespace create_media_systems --out src/main/resources
python tools/px_s1100/check_all.py src/main/resources create_media_systems
```

`layout.json` uses model pixels at 16 pixels per block. The front faces north in the authored model and the bass half occupies the block to the east. The renderer and block both use this layout.
