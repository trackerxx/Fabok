# FB WebView App

এই প্রজেক্ট দিয়ে GitHub Actions ব্যবহার করে একটা সিম্পল Android APK বানানো যাবে যেটা শুধু m.facebook.com লোড করে।

## ব্যবহারের ধাপ

1. GitHub-এ একটা নতুন **repository** বানান (public বা private, দুটোই চলবে)।
2. এই ফোল্ডারের সব ফাইল ও সাব-ফোল্ডার (`.github` সহ) সেই repository-তে আপলোড করুন।
   - GitHub ওয়েবসাইট থেকে "Add file" -> "Upload files" দিয়ে সরাসরি আপলোড করা যায়, অথবা
   - `git` কমান্ড লাইন দিয়ে push করতে পারেন:
     ```
     git init
     git add .
     git commit -m "first commit"
     git branch -M main
     git remote add origin <YOUR_REPO_URL>
     git push -u origin main
     ```
3. আপলোড হয়ে গেলে GitHub repo-র উপরে **"Actions"** ট্যাবে যান।
4. "Build APK" workflow-টা অটোমেটিক রান হবে (push করার সাথে সাথে)। যদি না হয়, "Run workflow" বাটনে ক্লিক করুন।
5. রান শেষ হলে (২-৩ মিনিট লাগতে পারে), সেই workflow run-এর পেজে নিচে **"Artifacts"** সেকশনে `fb-webview-app-debug` নামে একটা ফাইল পাবেন — ওটা ডাউনলোড করুন।
6. ডাউনলোড হওয়া zip ফাইলের ভেতরে `app-debug.apk` থাকবে — এটাই আপনার অ্যাপ।
7. ফোনে এই APK কপি করে ইনস্টল করুন (প্রথমবার "Install from unknown sources" পারমিশন চাইতে পারে, সেটা allow করে দিন)।

## নোট
- এই APK একটা "debug" ভার্সন — নিজের ব্যবহারের জন্য যথেষ্ট, Play Store-এ দেওয়ার জন্য উপযুক্ত নয়।
- অ্যাপটা ওপেন করলে সরাসরি Facebook mobile সাইট (m.facebook.com) লোড হবে এবং লগইন সেশন মনে রাখবে।
- অ্যাপের নাম ও আইকন পরে চাইলে বদলানো যাবে (`strings.xml` আর `mipmap` ফোল্ডারে)।
