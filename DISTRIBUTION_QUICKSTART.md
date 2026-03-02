# Firebase Distribution - Quick Start

Get your shareable install link in **3 steps** (15 minutes):

## 1. Create Firebase Project (5 min)

1. Go to https://console.firebase.google.com/
2. Create new project called "Verdure"
3. Add Android app with package name: `com.verdure`
4. Download `google-services.json` file

## 2. Add GitHub Secrets (5 min)

Go to GitHub → Settings → Secrets → Actions → New secret:

| Secret Name | How to Get It |
|-------------|---------------|
| **FIREBASE_CONFIG** | Open downloaded `google-services.json`, copy entire contents |
| **FIREBASE_APP_ID** | Firebase Console → Project Settings → Your apps → "App ID" (format: `1:123...:android:abc...`) |
| **FIREBASE_TOKEN** | Run `firebase login:ci` in terminal (requires `npm install -g firebase-tools`) |

## 3. Push to Main & Get Link (5 min)

```bash
git push origin main
```

Wait for GitHub Actions to complete, then:

1. Firebase Console → App Distribution → Releases
2. Click latest release → "Distribute" → Copy link
3. Share with testers!

**Link format:** `https://appdistribution.firebase.dev/i/abc123def456`

---

**Full documentation:** See `FIREBASE_SETUP.md` for detailed troubleshooting and options.

## What Testers See

When someone clicks your link:
1. Firebase page opens (mobile-friendly)
2. Sign in with Google
3. Tap "Download" button
4. Install Verdure
5. Done!

**No "Unknown Sources" warning** - Firebase is trusted by Android.

## Benefits

- ✅ Shareable links (no email invitations required)
- ✅ Automatic uploads on every push to main
- ✅ Free forever (unlimited uploads/downloads)
- ✅ Links never expire
- ✅ Track who downloaded and when
- ✅ Add release notes automatically

## Quick Commands

**Get Firebase token:**
```bash
npx firebase-tools login:ci
```

**Manual upload (without GitHub Actions):**
```bash
cd VerdureApp
./gradlew appDistributionUploadDebug
```

**Check Firebase status:**
```bash
firebase projects:list
firebase apps:list ANDROID
```

---

**Need help?** Full troubleshooting in `FIREBASE_SETUP.md`
