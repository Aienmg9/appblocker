# 🛡️ Appblocker

> ⚠️ **UWAGA / WARNING** ⚠️
>
> Aplikacja, ze względu na swoje głębokie ingerencje w system (blokowanie innych aplikacji, uprawnienia Accessibility Service), może zostać rozpoznana przez systemy antywirusowe jako oprogramowanie typu **ransomware** lub **malware**.
>
> **Source code aplikacji jest w pełni jawny.** Jeżeli chcesz się upewnić co do bezpieczeństwa tego narzędzia, zachęcamy do przejrzenia kodu źródłowego. W razie jakichkolwiek wątpliwości możesz skopiować kod z folderu `src` i wrzucić go do dowolnego modelu językowego (AI) z pytaniem, czy dany kod zawiera złośliwe funkcje.

---

**Appblocker** to zaawansowana aplikacja na system Android, zaprojektowana, aby pomóc użytkownikom odzyskać kontrolę nad czasem spędzanym przed ekranem. Łączy w sobie mechanizmy psychologiczne (opóźnianie gratyfikacji) z rygorystycznymi blokadami systemowymi, tworząc skuteczną barierę przed rozpraszającymi cyfrowymi nawykami.

---

## 🚀 Kluczowe Funkcje w Detalach

### 1. Inteligentne Blokowanie Aplikacji
*   **Grupy z Limitami**: Twórz grupy (np. "Entertainment", "Social Media") i ustawiaj dla nich wspólny dzienny limit czasu (np. 30 minut na wszystkie apki z danej grupy).
*   **Blokada Indywidualna**: Blokuj wybrane aplikacje na stałe bez względu na czas użycia.
*   **Monitorowanie w Czasie Rzeczywistym**: Dzięki `UsageStatsManager` aplikacja precyzyjnie liczy czas spędzony "wewnątrz" zablokowanych procesów.

### 2. Zaawansowany Hardcore Mode 🔒
To unikalna funkcja dla osób potrzebujących silnej dyscypliny:
*   **Strażnik Usług (Anti-Cheat)**: Wykorzystuje `WorkManager` i `AccessibilityGuardWorker`, aby w tle monitorować stan Usługi Ułatwień Dostępu. Jeśli użytkownik ją wyłączy, "Enforcer" wymusi jej ponowne włączenie, blokując dostęp do telefonu.
*   **Blokada Ustawień**: Uniemożliwia wejście do ustawień systemowych (opcjonalnie), co zapobiega ręcznemu wyłączeniu uprawnień aplikacji.
*   **Mechanizm Cooldown**: Jeśli spróbujesz wyłączyć blokadę, aplikacja wymusi odczekanie (domyślnie 60 minut) zanim przycisk "OFF" stanie się aktywny.
*   **Weryfikacja NFC**: Do odblokowania wymagane jest przyłożenie wcześniej sparowanego tagu/karty NFC. Mechanizm opiera się na dopasowaniu unikalnego **UID** zapisanego w bazie Room.
*   **Auto Grayscale**: Automatycznie przełącza ekran w tryb czarno-biały po aktywacji blokady. Realizowane systemowo przez `Settings.Secure` (wymaga jednorazowego nadania uprawnień przez ADB).

### 3. Blokowanie Stron WWW
*   **Głęboka Inspekcja URL**: Usługa ułatwień dostępu (Accessibility) skanuje pasek adresu w popularnych przeglądarkach (Chrome, Samsung Browser, Firefox, Opera).
*   **Dopasowanie Słów Kluczowych**: Blokuje dostęp do konkretnych domen lub stron zawierających zakazane frazy.

### 4. Focus Mode (Biała Lista)
Tryb ekstremalnej koncentracji, który odwraca logikę działania – wszystkie aplikacje są blokowane, z wyjątkiem tych, które sam dodałeś do "Białej Listy" (np. Telefon, Kalendarz, Notatki).

### 5. Inteligentne Harmonogramy
*   Planuj sesje blokady na konkretne godziny.
*   Zarządzane przez `ScheduleReceiver`, który aktywuje/dezaktywuje blokady globalnie lub dla konkretnych grup aplikacji na podstawie sygnałów z systemu.

---

## 🛠 Architektura Techniczna

### Serce Systemu: `AppBlockerService`
Główna usługa typu `AccessibilityService`, która:
1.  Nasłuchuje zdarzeń `TYPE_WINDOW_STATE_CHANGED`.
2.  Weryfikuje, czy aktualnie otwarta aplikacja znajduje się w `blockedCache`.
3.  **Synchronizacja danych**: Usługa posiada wewnętrzny cache reguł (`blockedCache`, `groupCache`, etc.), który jest automatycznie odświeżany z bazy Room przy każdym połączeniu usługi lub zmianie ustawień przez użytkownika (via `BroadcastReceiver`).
4.  Zarządza nakładką systemową (`WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`), która przykrywa zablokowaną treść.

### Samonaprawiający się Mechanizm: `WorkManager`
Aplikacja wykorzystuje `AccessibilityGuardWorker` do okresowego sprawdzania uprawnień w tle. W przypadku wykrycia próby obejścia blokad (np. wyłączenie Accessibility Service), uruchamia `AccessibilityEnforcerActivity`, która:
*   Blokuje gesty nawigacyjne (Back).
*   Wymusza powrót do ustawień w celu przywrócenia ochrony.
*   Nalicza dodatkowe kary czasowe (`extra_penalty_mins`) w przypadku wykrycia "oszukiwania" (używania apek przy wyłączonej usłudze).

### Warstwa Danych i Bezpieczeństwo
Aplikacja korzysta z relacyjnej bazy danych Room (SQLite) do przechowywania:
*   **BlockedApp**: Lista zablokowanych pakietów.
*   **AppGroup**: Konfiguracja grup i limitów czasowych.
*   **BlockedSchedule**: Zdefiniowane harmonogramy blokad.
*   **FocusWhitelist**: Biała lista dla trybu Focus.
*   **BlockedWebsite**: Zablokowane domeny WWW.
*   **NfcCard**: UID kart NFC do autoryzacji.

**Mechanizmy ochrony danych:**
*   **Szyfrowanie SQLCipher**: Baza danych jest w pełni zaszyfrowana przy użyciu biblioteki `SQLCipher`. Kluczem szyfrującym jest unikalny `ANDROID_ID` urządzenia, co uniemożliwia odczytanie bazy po skopiowaniu na inny telefon.
*   **Przechowywanie w Dokumentach**: Jeśli użytkownik nada uprawnienie "Dostęp do wszystkich plików" (`MANAGE_EXTERNAL_STORAGE`), baza danych jest przechowywana w folderze `Documents/AppBlocker/appblocker.db`. Pozwala to na zachowanie ustawień nawet po reinstalacji aplikacji.
*   **Automatyczna Migracja**: System automatycznie przenosi bazę z pamięci wewnętrznej do publicznej przy pierwszym uruchomieniu z odpowiednimi uprawnieniami, dbając o spójność szyfrowania.

---

## 🔑 Uprawnienia (Android Manifest)

Aplikacja wymaga szeregu krytycznych uprawnień do poprawnego działania:
*   `BIND_ACCESSIBILITY_SERVICE`: Kluczowe do wykrywania otwieranych aplikacji i blokowania stron WWW.
*   `SYSTEM_ALERT_WINDOW`: Pozwala na wyświetlanie nakładki (overlay) blokującej dostęp do aplikacji.
*   `PACKAGE_USAGE_STATS`: Niezbędne do precyzyjnego liczenia czasu spędzonego w aplikacjach (limity grupowe).
*   `MANAGE_EXTERNAL_STORAGE`: Umożliwia przechowywanie zaszyfrowanej bazy danych w folderze Dokumenty (trwałość danych).
*   `QUERY_ALL_PACKAGES`: Pozwala na pobranie listy wszystkich zainstalowanych aplikacji w celu ich konfiguracji.
*   `NFC`: Wykorzystywane do fizycznej autoryzacji odblokowania.
*   `BIND_DEVICE_ADMIN`: Zapobiega łatwemu odinstalowaniu aplikacji.
*   `RECEIVE_BOOT_COMPLETED`: Pozwala na automatyczne uruchomienie strażnika po restarcie telefonu.
*   `WRITE_SECURE_SETTINGS` (via ADB): Wymagane do działania funkcji **Auto Grayscale**.
*   `POST_NOTIFICATIONS`: Informowanie o aktywnych blokadach i stanie strażnika.

---

## 📂 Struktura Katalogów

```text
app/src/main/java/com/Aien/appblocker/
├── AppBlockerService.kt            # Logika blokowania, overlay i URL scanning
├── AccessibilityGuardWorker.kt     # Strażnik sprawdzający stan usług w tle (WorkManager)
├── AccessibilityEnforcerActivity.kt # Ekran blokady wymuszający re-aktywację usług
├── MainActivity.kt                 # Główne UI i logika NFC
├── AppDatabase.kt                  # Konfiguracja bazy danych Room
├── AppBlockerDao.kt                # Interfejs dostępu do danych (DAO)
├── ...Adapter.kt                   # Adaptery UI (App, Group, Website, Schedule)
├── ...Receiver.kt                  # Odbiorcy zdarzeń (Schedule, DeviceAdmin)
└── (Modele).kt                     # Encje Room (BlockedApp, AppGroup, NfcCard, etc.)
```

---

## 📝 Instrukcja dla Programistów

### Budowanie projektu
1.  Wymagane **Android Studio Koala** lub nowsze.
2.  JDK 17.
3.  Gradle 8.0+.

### Konfiguracja ADB (Auto Grayscale)
Aby funkcja Auto Grayscale działała poprawnie, należy nadać aplikacji uprawnienie `WRITE_SECURE_SETTINGS` za pomocą komendy:
```bash
adb shell pm grant com.Aien.appblocker android.permission.WRITE_SECURE_SETTINGS
```

### Testowanie na fizycznym urządzeniu
Ze względu na wykorzystanie **NFC**, **Accessibility Services** oraz **WorkManager**, zaleca się testowanie na fizycznym telefonie:
1.  Włącz debugowanie USB.
2.  Zainstaluj aplikację.
3.  **Ważne**: W ustawieniach systemowych aplikacji włącz "Zezwalaj na ustawienia ograniczone" (Android 13+), aby móc aktywować Usługę Ułatwień Dostępu.

---
