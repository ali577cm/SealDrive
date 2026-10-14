# SealDrive (unofficial)

A small Android app that shows your own Overdrive page fullscreen, without browser chrome.
On first launch it asks for your Overdrive address (e.g. `http://100.x.x.x:8080`); the address is stored only on your device.

## Disclaimer
- **Unofficial and unaffiliated.** This is an independent hobby project. It is not made, endorsed or supported by
  Overdrive, its author, BYD, or Tailscale. All names, logos and trademarks belong to their respective owners.
- **No warranty.** The software is provided "as is", with no guarantee that it is safe, secure, correct or fit for any purpose.
  Use it at your own risk. The author accepts no liability for any damage, data loss, vehicle behaviour or security issue
  arising from its use.
- **Not for use while driving.** Don't interact with it while driving, and follow the law and your vehicle's guidance.
- **Self-updating.** The app can download and offer to install new versions from this repository's GitHub Releases.
  Only install updates if you trust this repository. Review the source before installing.
- **Your network.** The app only connects to the address you enter. Keep your Tailscale access controls tight.

## Updates
Every push to `main` builds a signed APK and publishes it as a GitHub Release; the app offers it on launch.
The workflow needs repo secrets `KEYSTORE_B64` and `KEYSTORE_PASSWORD`.

## License
MIT, see [LICENSE](LICENSE). The app icon is an original illustration and is not an official logo of any brand.
