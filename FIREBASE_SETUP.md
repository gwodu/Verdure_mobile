# Firebase App Distribution Setup Guide

This guide will help you set up Firebase App Distribution for Verdure, enabling 1-click install links for testers.

## Overview

Once configured, every push to `main` will automatically:
1. Build the APK via GitHub Actions
2. Upload it to Firebase App Distribution
3. Generate a shareable install link
4. Notify your testers (optional)

## Prerequisites

- Google account (free)
- 15 minutes setup time

## Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click "Add project" or "Create a project"
3. Enter project name: **Verdure** (or your choice)
4. Disable Google Analytics (not needed for App Distribution)
5. Click "Create project"

## Step 2: Add Android App to Firebase

1. In Firebase Console, click "Add app" → Android icon
2. Enter details:
   - **Android package name**: `com.verdure` (must match exactly)
   - **App nickname**: Verdure (optional)
   - **Debug signing certificate**: Leave blank for now
3. Click "Register app"
4. **Download `google-services.json`** (critical file!)
5. Keep this file safe - you'll need it in Step 4

## Step 3: Enable Firebase App Distribution

1. In Firebase Console, go to **Release & Monitor** → **App Distribution**
2. Click "Get started"
3. Add tester groups:
   - Click "Groups" tab
   - Click "Add group"
   - Name it: **testers**
   - Add email addresses of people who should receive install links
   - Click "Save"

## Step 4: Configure GitHub Secrets

GitHub Actions needs 3 secrets to upload builds to Firebase:

### 4a. Get Firebase Token

Run this command in your terminal (requires Firebase CLI):

```bash
# Install Firebase CLI if not already installed
npm install -g firebase-tools

# Login and get CI token
firebase login:ci
```

This will open a browser for authentication, then output a token like:
```
1//abc123def456...xyz789
```

**Copy this token** - you'll add it to GitHub Secrets as `FIREBASE_TOKEN`.

### 4b. Get Firebase App ID

1. In Firebase Console → Project Settings → Your apps
2. Find your Android app (com.verdure)
3. Copy the **App ID** (format: `1:123456789:android:abc123def456`)

### 4c. Add Secrets to GitHub

1. Go to your GitHub repository: `https://github.com/YOUR_USERNAME/Verdure`
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Click "New repository secret" and add these 3 secrets:

| Secret Name | Value | Where to Find It |
|-------------|-------|------------------|
| `FIREBASE_CONFIG` | Full contents of `google-services.json` | Downloaded in Step 2 |
| `FIREBASE_APP_ID` | Your Firebase App ID | Firebase Console → Project Settings |
| `FIREBASE_TOKEN` | CI token from `firebase login:ci` | Terminal output from Step 4a |

**For FIREBASE_CONFIG:**
- Open the `google-services.json` file you downloaded
- Copy the ENTIRE file contents (all JSON)
- Paste it as the secret value

## Step 5: Verify Setup

1. Push a commit to `main` branch (or manually trigger workflow)
2. Go to **Actions** tab in GitHub
3. Wait for build to complete (~3-5 minutes)
4. Check Firebase Console → App Distribution
5. You should see the new build appear!

## Step 6: Get Your Install Link

1. In Firebase Console → App Distribution → Releases
2. Click on the latest release
3. Click "Distribute" → Share link
4. Copy the link (format: `https://appdistribution.firebase.dev/...`)
5. Share this link with testers!

**When testers click the link:**
1. Opens Firebase App Distribution page
2. Sign in with Google (required)
3. Tap "Download" button
4. Install APK (may need to enable "Install from Unknown Sources")
5. Done!

## Optional: Auto-notify Testers

To automatically email testers when new builds are uploaded:

1. Firebase Console → App Distribution → Testers & Groups
2. Enable "Notify testers by email"
3. Testers will receive emails with install links automatically

## Troubleshooting

### "Authentication failed" in GitHub Actions

**Problem:** `FIREBASE_TOKEN` is invalid or expired

**Solution:**
1. Run `firebase login:ci` again
2. Update `FIREBASE_TOKEN` secret in GitHub
3. Re-run workflow

### "App not found" error

**Problem:** `FIREBASE_APP_ID` doesn't match your Firebase project

**Solution:**
1. Verify App ID in Firebase Console → Project Settings
2. Must start with `1:` and end with `:android:...`
3. Update secret in GitHub

### Build succeeds but no upload

**Problem:** Secrets not configured or branch is not `main`

**Solution:**
1. Verify all 3 secrets are set correctly
2. Firebase upload only runs on `main` branch pushes (not PRs)
3. Check Actions logs for skip messages

### "google-services.json not found" during build

**Problem:** `FIREBASE_CONFIG` secret not set

**Solution:**
1. Add the secret (see Step 4c)
2. Must be complete, valid JSON
3. Re-run workflow

## Alternative: Manual Upload (Without GitHub Actions)

If you prefer to upload manually:

```bash
# Build APK locally
cd VerdureApp
./gradlew assembleDebug

# Upload to Firebase
firebase appdistribution:distribute \
  app/build/outputs/apk/debug/app-debug.apk \
  --app YOUR_FIREBASE_APP_ID \
  --groups testers \
  --release-notes "Manual build $(date)"
```

## Cost

Firebase App Distribution is **completely free**:
- Unlimited uploads
- Unlimited testers
- Unlimited downloads
- No expiration (unlike GitHub artifacts which expire after 30 days)

## Next Steps

Once Firebase is working, you can:
1. Add more tester groups (e.g., "internal", "beta", "family")
2. Set up release notes automation
3. Integrate with Crashlytics for crash reporting
4. Eventually transition to Google Play Store for public release

---

**Questions?** Check the [Firebase App Distribution docs](https://firebase.google.com/docs/app-distribution/android/distribute-gradle)
