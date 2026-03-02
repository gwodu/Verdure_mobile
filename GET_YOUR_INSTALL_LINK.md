# Get Your 1-Click Install Link

Follow these steps to enable shareable install links for Verdure.

## 📋 Quick Checklist (15 minutes)

```
☐ Create Firebase project
☐ Download google-services.json  
☐ Get Firebase token (firebase login:ci)
☐ Add 3 secrets to GitHub
☐ Merge to main
☐ Copy install link from Firebase
```

---

## Step 1: Create Firebase Project (3 min)

1. Visit: https://console.firebase.google.com/
2. Click **"Add project"**
3. Name: `Verdure` (or anything you prefer)
4. **Disable** Google Analytics (not needed)
5. Click **"Create project"**

## Step 2: Add Android App (2 min)

1. Click **"Add app"** → Android icon (robot)
2. Fill in:
   - **Package name**: `com.verdure` ⚠️ **Must match exactly!**
   - **App nickname**: `Verdure` (optional)
3. Click **"Register app"**
4. **Download `google-services.json`** 📥
5. Save this file somewhere safe!

## Step 3: Get Firebase Token (5 min)

Open your terminal and run:

```bash
# Install Firebase CLI (if you don't have it)
npm install -g firebase-tools

# Login and get token
firebase login:ci
```

This opens a browser to sign in with Google, then outputs:

```
✔ Success! Use this token to login on a CI server:
1//abc123def456...xyz789
```

**Copy this entire token!** 📋

## Step 4: Add GitHub Secrets (5 min)

1. Go to: `https://github.com/YOUR_USERNAME/Verdure/settings/secrets/actions`
2. Click **"New repository secret"** three times to add:

### Secret 1: FIREBASE_CONFIG
- **Name**: `FIREBASE_CONFIG`
- **Value**: Paste entire contents of `google-services.json`
  - Open the file in a text editor
  - Select all (Ctrl+A or Cmd+A)
  - Copy (Ctrl+C or Cmd+C)
  - Paste in GitHub secret value box

### Secret 2: FIREBASE_APP_ID
- **Name**: `FIREBASE_APP_ID`
- **Value**: Your Firebase App ID
- **Where to find**: Firebase Console → ⚙️ Settings → Your apps → "App ID"
- **Format**: `1:123456789012:android:abc123def456789xyz`

### Secret 3: FIREBASE_TOKEN
- **Name**: `FIREBASE_TOKEN`
- **Value**: Token from Step 3 (`firebase login:ci` output)
- **Format**: `1//abc123def456...xyz789`

## Step 5: Trigger Build

Push this branch to main to trigger the automated build:

```bash
git checkout main
git merge cursor/verdure-install-link-baa5
git push origin main
```

**Or** trigger manually:
1. Go to GitHub → Actions tab
2. Click "Build Android APK" workflow
3. Click "Run workflow" → Select `main` branch → "Run workflow"

## Step 6: Get Your Install Link! 🎉

1. Wait for GitHub Actions to complete (~3-5 minutes)
2. Go to Firebase Console: https://console.firebase.google.com/
3. Navigate to: **Release & Monitor** → **App Distribution** → **Releases**
4. Click on the latest release (should show today's date)
5. Click **"Copy link"** button 📋

Your link looks like:
```
https://appdistribution.firebase.dev/i/abc123def456
```

**Share this link with anyone!** They can install Verdure with just a few taps.

---

## What Testers Experience

When someone clicks your link:

1. **Firebase page opens** (mobile-friendly)
2. **"Accept invite"** button appears
3. Tap → **Sign in with Google** (required)
4. Tap **"Download"** (APK downloads)
5. Tap **"Install"** (may need to allow unknown sources)
6. **Open Verdure** → Grant permissions → Done! ✨

**No complicated setup** - just click, sign in, install!

---

## Adding Testers (Optional)

To organize testers into groups:

1. Firebase Console → App Distribution → **Testers & Groups**
2. Click **"Add group"**
3. Name: `testers` (or `beta`, `internal`, etc.)
4. Add email addresses
5. Click **"Save"**

**Why use groups?**
- Distribute to specific people (e.g., internal team vs public beta)
- Track who downloaded and when
- Send release notes to specific groups

---

## Future: Automatic Notifications

Enable email notifications when new builds are ready:

1. Firebase Console → App Distribution → **Releases**
2. Click latest release → **"Distribute"**
3. Select tester groups
4. Add release notes
5. Click **"Distribute"**

Testers get email: "New Verdure build available! [Download]"

---

## Quick Reference

| What | Where |
|------|-------|
| **Firebase Console** | https://console.firebase.google.com/ |
| **GitHub Actions** | https://github.com/YOUR_USERNAME/Verdure/actions |
| **Secrets Settings** | https://github.com/YOUR_USERNAME/Verdure/settings/secrets/actions |
| **Full Docs** | See `FIREBASE_SETUP.md` |

---

**Need help?** See `FIREBASE_SETUP.md` for detailed troubleshooting.
