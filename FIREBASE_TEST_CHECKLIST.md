# Firebase App Distribution - Test Checklist

Use this checklist to verify the Firebase setup is working correctly.

## Pre-Flight Checks

- [ ] Firebase project created at console.firebase.google.com
- [ ] Android app registered with package name `com.verdure`
- [ ] `google-services.json` downloaded from Firebase
- [ ] Firebase CLI installed (`npm install -g firebase-tools`)
- [ ] Firebase token obtained (`firebase login:ci`)

## GitHub Secrets Configuration

Verify all 3 secrets are added to GitHub → Settings → Secrets → Actions:

- [ ] `FIREBASE_CONFIG` = Full contents of `google-services.json` file
- [ ] `FIREBASE_APP_ID` = Firebase App ID (format: `1:123456789:android:abc123def456`)
- [ ] `FIREBASE_TOKEN` = Token from `firebase login:ci` command

**How to verify secrets are set:**
1. Go to GitHub repo → Settings → Secrets and variables → Actions
2. You should see 3 secrets listed (values are hidden, that's normal)
3. Names must match exactly (case-sensitive)

## Testing the Workflow

### Test 1: Merge to Main

1. Merge this branch to `main`:
   ```bash
   git checkout main
   git merge cursor/verdure-install-link-baa5
   git push origin main
   ```

2. Watch GitHub Actions:
   - Go to Actions tab
   - Click on the running workflow
   - Monitor each step

3. Expected results:
   - ✅ Checkout succeeds
   - ✅ JDK 21 setup succeeds
   - ✅ Firebase config injection succeeds (if secrets configured)
   - ✅ Build Debug APK succeeds
   - ✅ Upload to GitHub Artifacts succeeds
   - ✅ Upload to Firebase App Distribution succeeds (if secrets configured)

### Test 2: Verify Firebase Upload

1. Go to Firebase Console → App Distribution → Releases
2. You should see a new release with:
   - Version: 1.0-prototype
   - Build number matching GitHub commit
   - Release notes from RELEASE_NOTES.txt

### Test 3: Get Install Link

1. Click on the release in Firebase Console
2. Click "Distribute" button
3. Copy the install link
4. Open link on Android device (or send to tester)
5. Sign in with Google
6. Tap "Download"
7. Install APK
8. Open Verdure and verify it works

## Troubleshooting

### Build succeeds but no Firebase upload

**Check:**
- Are you on `main` branch? (Firebase upload only runs on main, not PRs or other branches)
- Are secrets configured? Check Actions logs for "Skipping Firebase upload" message

### "Invalid google-services.json" error

**Fix:**
- Verify `FIREBASE_CONFIG` secret contains valid JSON (no line breaks, complete file)
- Re-download from Firebase Console → Project Settings → Your apps → Download google-services.json
- Copy entire file contents to secret (Ctrl+A, Ctrl+C)

### "Authentication failed" during upload

**Fix:**
- Regenerate token: `firebase login:ci`
- Update `FIREBASE_TOKEN` secret in GitHub
- Re-run workflow

### APK builds but upload step is skipped

**Check GitHub Actions logs:**
```
if: secrets.FIREBASE_CONFIG != '' && secrets.FIREBASE_APP_ID != '' && github.ref == 'refs/heads/main'
```

This step only runs when:
1. FIREBASE_CONFIG secret is set
2. FIREBASE_APP_ID secret is set
3. FIREBASE_TOKEN secret is set (in env)
4. Branch is `main` (not a PR or other branch)

## Success Criteria

✅ **Setup is successful when:**
1. GitHub Actions workflow completes without errors
2. APK appears in Firebase Console → App Distribution
3. Install link works on test device
4. App installs and runs correctly
5. Future pushes to main automatically upload new builds

## Manual Upload Alternative

If GitHub Actions upload fails, you can upload manually:

```bash
cd VerdureApp
./gradlew assembleDebug
firebase appdistribution:distribute \
  app/build/outputs/apk/debug/app-debug.apk \
  --app YOUR_FIREBASE_APP_ID \
  --groups testers \
  --release-notes "Manual test upload"
```

This helps debug whether the issue is with GitHub Actions or Firebase config.

## Next Steps After Success

1. **Add testers:**
   - Firebase Console → App Distribution → Testers & Groups
   - Create "testers" group
   - Add email addresses

2. **Share install link:**
   - Copy link from Firebase Console
   - Send to testers via email/Slack/etc.

3. **Enable auto-notifications:**
   - Firebase Console → App Distribution → Settings
   - Toggle "Notify testers by email when new builds are available"

4. **Monitor adoption:**
   - Firebase Console → App Distribution → Dashboard
   - See who downloaded, when, and on what devices

---

**Questions?** See detailed guide in `FIREBASE_SETUP.md`
