# Report Notify

Στείλε **HTML αναφορές** από τον υπολογιστή σου στο κινητό σου (Android) ως notifications, με **κρυπτογράφηση end-to-end** και **χωρίς δικό σου server**.

Όταν συμβαίνει κάτι σε μια τοπική εφαρμογή (backup, έλεγχος, script), καλείς μία εντολή. Στο κινητό έρχεται notification, και αν το πατήσεις ανοίγει η αναφορά μορφοποιημένη, με CSS, πίνακες, εικόνες και JavaScript. Όλες οι αναφορές μένουν σε λίστα μέσα στην εφαρμογή.

```
event στο PC ──► send_report.py ──AES-256-GCM──► ntfy.sh ──► εφαρμογή Android ──► notification ──► WebView
```

## Πώς λειτουργεί

- **Μεταφορά:** το δημόσιο [ntfy.sh](https://ntfy.sh) μεταφέρει μόνο τα κρυπτογραφημένα δεδομένα. Δουλεύει πίσω από NAT και CGNAT, σε Wi-Fi ή σε 4G/5G, χωρίς ρυθμίσεις στο router.
- **Κρυπτογράφηση:** κάθε αναφορά κρυπτογραφείται στο PC με AES-256-GCM, με κλειδί που υπάρχει μόνο στο PC και στο κινητό. Το ntfy.sh βλέπει μόνο ένα αρχείο `r.bin` με τυχαία bytes. Το GCM εξασφαλίζει επίσης ότι το κινητό δέχεται μόνο αναφορές από το δικό σου PC.
- **Pairing:** γίνεται μία φορά, με σάρωση QR.
- **Background:** η εφαρμογή κρατά μια σύνδεση ανοιχτή μέσω foreground service, οπότε οι αναφορές έρχονται και όταν η εφαρμογή είναι κλειστή ή μετά από επανεκκίνηση. Όσες σταλούν όσο το κινητό είναι offline παραδίδονται όταν ξανασυνδεθεί, εφόσον αυτό γίνει μέσα σε περίπου 12 ώρες.

## Εγκατάσταση

### 1. Κινητό

Κατέβασε το `report-notify-vX.Y.Z.apk` από τα [**Releases**](../../releases/latest) και εγκατέστησέ το. Θα χρειαστεί να επιτρέψεις την «εγκατάσταση από άγνωστες πηγές».

Απαιτήσεις: Android 8.0 ή νεότερο, με Google Play services (για τον σαρωτή QR).

### 2. Υπολογιστής

Χρειάζεσαι μόνο το [uv](https://docs.astral.sh/uv/). Οι εξαρτήσεις δηλώνονται μέσα στο script, οπότε δεν χρειάζεται venv ή `pip install`.

```bash
git clone https://github.com/tedlaz/report-notify
cd report-notify/pc
uv run send_report.py --genkey     # δημιουργεί το config.json και ανοίγει το QR
```

### 3. Pairing

Στην εφαρμογή πάτα **Σάρωση QR** και σκάναρε τον κωδικό. Στη συνέχεια δέξου:
- τις **ειδοποιήσεις**,
- την **εξαίρεση από τη βελτιστοποίηση μπαταρίας**, αλλιώς το Android κόβει τη σύνδεση και οι αναφορές καθυστερούν.

Για να ξαναδείξεις το QR αργότερα: `uv run send_report.py --qr`. Για νέο pairing από την εφαρμογή, πάτα το κουμπί QR πάνω δεξιά.

> ⚠️ Το `pc/config.json` περιέχει το κλειδί. Μην το ανεβάσεις πουθενά (είναι στο `.gitignore`) και κράτα αντίγραφο ασφαλείας.

## Χρήση

```bash
uv run send_report.py report.html "Backup ολοκληρώθηκε"
my_script | uv run send_report.py - "Νυχτερινός έλεγχος"   # HTML από stdin
```

Δοκιμή με το παράδειγμα: `uv run send_report.py test.html "Δοκιμή"`.

Κλήση από άλλη εφαρμογή, π.χ. Python:

```python
import subprocess
subprocess.run(
    ["uv", "run", "path/to/pc/send_report.py", "-", "Τίτλος"],
    input=html.encode("utf-8"), check=True,
)
```

**Συμβουλές για ωραίες αναφορές:**
- Βάλε `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- Ενσωμάτωσε το CSS και τις εικόνες (`data:` URIs). Η αναφορά πρέπει να είναι αυτόνομη.
- Ο **τίτλος δεν κρυπτογραφείται**, γιατί το ntfy.sh τον χρειάζεται για το notification. Κράτα τον γενικό και βάλε τα ευαίσθητα στοιχεία μέσα στο HTML.

## Όρια

| | |
|---|---|
| Μέγεθος αναφοράς | έως ~15 MB (όριο attachment του ntfy.sh) |
| Offline κινητό | οι αναφορές κρατιούνται στο ntfy.sh για ~12 ώρες |
| iOS | δεν υποστηρίζεται: το iOS δεν επιτρέπει σε εφαρμογές να κρατούν σύνδεση στο background |

Αν θες να μην εξαρτάσαι από το ntfy.sh, μπορείς να στήσεις δικό σου ntfy server. Αρκεί να αλλάξεις το `server` στο `config.json` και να κάνεις νέο pairing.

## Build

Το APK χτίζεται στο **GitHub Actions** (`.github/workflows/build.yml`), οπότε δεν χρειάζεται Android Studio:

- **push στο `main`:** dev build, διαθέσιμο ως artifact.
- **tag `vX.Y.Z`:** build με αυτή την έκδοση και δημιουργία **GitHub Release** με συνημμένο το APK.

```bash
git tag v1.2.0 && git push origin v1.2.0
```

Για fork, ρύθμισε τα secrets `KEYSTORE_B64` και `KEYSTORE_PASS` (alias `report`):

```bash
keytool -genkeypair -keystore release.keystore -alias report -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore | gh secret set KEYSTORE_B64
gh secret set KEYSTORE_PASS
```

Κράτα το keystore: αν αλλάξει, οι νέες εκδόσεις δεν εγκαθίστανται πάνω στις παλιές.

Tests: `uv run pc/test_crypto.py` τοπικά. Τα unit tests της εφαρμογής τρέχουν στο CI. Και οι δύο πλευρές ελέγχουν το ίδιο test vector, οπότε το format της κρυπτογράφησης είναι εγγυημένα κοινό.

## Δομή

```
pc/send_report.py          αποστολή, --genkey, --qr (uv script)
pc/test_crypto.py          test κρυπτογράφησης
pc/test.html               παράδειγμα αναφοράς
app/src/main/java/...      ListenerService (σύνδεση ntfy), MainActivity, Core (crypto/αποθήκευση)
app/src/main/assets/ui.html  UI της εφαρμογής (λίστα, pairing)
```
