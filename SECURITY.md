# Security

BYDrive is an unofficial, personal-use app. It contains **no secrets**: the car's address, your tariff and your login live
only on the phone, and the release signing key lives only in the repository owner's GitHub Actions secrets.

## What the design relies on
- The car is reached only over **Tailscale**; the app refuses plain `http://` addresses outside Tailscale / private networks.
- Updates are only installed if signed with the original key (checked by the app and again by Android).
- Only the repository owner can run the release workflow; it has no pull-request trigger.
- Nothing is sent anywhere except to the address you configure and to GitHub (to check for updates).

## Reporting a problem
Please use GitHub's private vulnerability reporting for this repository rather than a public issue.
