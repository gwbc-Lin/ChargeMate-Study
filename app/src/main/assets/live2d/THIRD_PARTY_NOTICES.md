# Asset and dependency notices

The default public package contains only original ChargeMate SVG artwork (ChargeBot and ChargeCat), distributed under this project's MIT license. No game/anime models, user-imported models, or Live2D runtime binaries are included.

The default distribution includes Spine runtime binaries (`pixi7.min.js`, `pixi-spine.js`) and requires every user to obtain their own valid Spine Editor license under the otherwise/each-user branch of the Spine Runtimes License. Copyright (c) 2013-2025, Esoteric Software LLC. Full current license: licenses/Spine-Runtimes-2025-LICENSE.txt; original integration notice: licenses/Spine-LICENSE.txt.

Spine character models are not bundled. Users may import their own licensed models at runtime; this repository neither hosts nor fetches third-party game assets.

Android dependencies retain their respective licenses. See Gradle dependency declarations and upstream projects; the MIT license for ChargeMate does not relicense third-party dependencies.

## Runtime integrations and publication status

The source includes adapters for Live2D and Spine. Live2D Cubism Core is not bundled; prepare it separately in ignored local files for development. Live2D publication approval is pending; a Release build with Live2D enabled is blocked until the publisher explicitly confirms applicable approval. A build flag does not grant rights.

- Live2D Cubism Core: proprietary Live2D license. Expandable applications may require prior approval and a special publication agreement, including individual/free publishers. See https://www.live2d.com/en/sdk/license/expandable/ and https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html .
- Official Live2D sample characters (not bundled): terms and character-specific notices at https://www.live2d.com/eula/live2d-sample-model-terms_en.html . They are not public-domain models.
- pixi-live2d-display 0.4.0 wrapper (not bundled): MIT; embedded Cubism components retain Live2D terms.
- PixiJS 6.5.10 / 7.4.3: MIT.
- pixi-spine wrapper: MIT; embedded Spine runtime is subject to the Spine Runtimes License, not merely MIT. See https://esotericsoftware.com/spine-editor-license .

Reference copies of the licenses are retained in licenses/ for audit. They are not proof that a particular distributor has obtained the necessary publication rights. User imports remain private and do not transfer ownership or redistribution rights.
