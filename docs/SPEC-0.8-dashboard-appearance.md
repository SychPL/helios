# SPEC 0.8 — poprawki, redesign i tła: podział po recenzji

Status: **dokumentacja do recenzji, bez zmian aplikacji i wdrożenia**. Aktualizacja 15 września 2026 po recenzji Claude Code. Ten indeks zastępuje poprzedni zbiorczy projekt; jego sekcje o własnym panelu, bibliotece zdjęć i schemacie 4 nie obowiązują.

## Etapy

| Etap | Dokument | Zakres |
| --- | --- | --- |
| Najpierw hotfix 0.8.5 | [SPEC 0.8a](SPEC-0.8a-renderer-music.md) — punkty 2, 4.3 i 5 | Delty metadanych, zachowanie sesji po pauzie, unieważnianie pobrań i dekodowanie okładki raz |
| Następnie redesign | [SPEC 0.8a](SPEC-0.8a-renderer-music.md) — punkty 3–4 | Paleta, większy zegar i data, prawa nakładka, ikony wektorowe, spójne dialogi |
| Niezależnie tła | [SPEC 0.8b](SPEC-0.8b-backgrounds.md) | Ustawienia per urządzenie w options flow, natywny wybór pliku, prywatny obraz i zdarzenie appearance |

Numer specyfikacji nie jest numerem APK. Hotfix ma wejść jako 0.8.5; zweryfikowana baza to 0.8.4, commit `48eecff`. Przed realizacją trzeba ponownie sprawdzić gałąź i uzgodnić ewentualne równoległe zmiany Claude Code. Nie blokować naprawy muzyki na tła. Samo przekazanie recenzji nie oznacza zgody na instalację APK.

## Rozstrzygnięcia recenzji

- Przyjęty podział zakresu; bez nowego schematu Lovelace. Starsze dokumenty 2/3 działają dalej.
- Przyjęte korekty: status muzyki wysokości 48, uchwyt jawnie nad kolumną 4/wierszem 2, rozmiar godziny 120 jako sufit, stałe krycie zamiast analizy luminancji w runtime.
- Paleta obejmuje również klawiaturę parowania i dialog urządzenia. Zapis wyglądu nie restartuje muzyki ani połączeń.
- Warunki domowych encji nie są kopiowane do speca renderera. Źródło: [raport dashboardu uwagi](../artifacts/attention-dashboard-20260915.md), w którym poprawiono sprzeczny opis tarasu; nie jest to nowa zmiana konfiguracji HA.
- Przyjęty kierunek options flow per urządzenie, ale **nie dosłowny wariant ImageSelector/image_upload**: w źródłach HA 2026.8.3 brak ImageSelector, serwowanie image_upload nie wymaga logowania, miniatury są tylko 256/512, a original zachowuje wejściowy plik.
- Proponowana prostsza alternatywa: FileSelector + standardowy file_upload, niewielkie przetwarzanie obrazu i chroniony GET w integracji. Nie wymaga własnego frontendu, galerii, skanowania YAML ani zapisu Lovelace. Nie obiecuje usunięcia całej logiki obrazów — walidacja, normalizacja i prywatność zostają.
- Etap 0.8b jest projektem z jawną bramką próby options flow. Nie wykonano testowego uploadu ani modyfikacji działającego HA.
- Uwaga o zbędności bumpu wersji nie jest już decyzją blokującą: nowy projekt nie dodaje appearance do YAML. Gdyby wrócić do zmiany schematu w przyszłości, jego wersjonowanie wymaga osobnej decyzji, nie polegania wyłącznie na błędzie „nieznane pole”.

## Kolejne działanie

Recenzja dwóch mniejszych specyfikacji, następnie osobny plan hotfixu. Dotychczasowy [plan wykonawczy](superpowers/plans/2026-09-15-dashboard-music-redesign.md) pozostaje wstrzymany i musi odwoływać się do właściwego etapu. Nie rozpoczynać równolegle prac nad wszystkimi komponentami.

Nie zmieniono ustaleń użytkownika: jedna aplikacja, większy zegar/data, muzyka jako prawa nakładka, pauza zachowująca panel, dolne powiadomienia 1×1 oraz wybór/wgranie tła z HA. Sterowanie muzyką przez Nabu jest oddzielnym problemem konfiguracji HA, nie częścią tego redesignu.
