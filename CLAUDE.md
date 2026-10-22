# FBR Shop Survey: working rules

- ALWAYS present a plan (what will change, step by step, in plain words) and WAIT for the
  user's explicit approval before writing or changing any code, files on the user's PC,
  or pushing/building. The user asked for this permanently.
- Clarification answers are not approval. Only proceed when the user clearly says yes / build.
- After a change: push to main (GitHub Actions builds the APK) and give the download link:
  https://github.com/Tajmalookcs/fbr-survey/releases/latest/download/FBR-Shop-Survey.apk
- Never put FBR data (FBR_Lookup.fbr / db.sqlite3) in this public repo.
