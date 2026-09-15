# Helios 0.2.0 — Okay Nabu

2026-09-15. Użytkownik potwierdził działanie rozmowy przyciskiem w poprzedniej wersji na zegarze.

- Dodano lokalny microWakeWord (ARMv7), model Okay Nabu, 16 kHz, krok 10 ms, cutoff 0.85, okno 5.
- Przełącznik nasłuchu zapisuje preferencję. Nasłuch tylko podczas widoczności Activity, po uprawnieniu mikrofonu i konfiguracji HA.
- Nasłuch i Assist używają pojedynczego executora: zwolnienie AudioRecord następuje przed uruchomieniem drugiego konsumenta mikrofonu. Zatrzymanie nasłuchu używa volatile flag i odczytu non-blocking.
- Callbacki starych sesji nasłuchu są ignorowane. Wyjście z Activity zatrzymuje nasłuch i anuluje Assist. Po Assist/TTS nasłuch wraca z opóźnieniem 1 s.
- `assembleDebug lintDebug`: PASS. Zweryfikowano bibliotekę w APK, hash przypiętego modelu, brak tokenu HA w APK oraz zgodność pobrania HTTP z plikiem wynikowym.
- Oczekuje na instalację aktualizacji i test fizycznego urządzenia: wykrycie hasła, odpowiedź HA, ponowne wybudzenie, przełącznik OFF i wyjście z aplikacji. Nie twierdzimy, że te scenariusze zostały już przetestowane w APK 0.2.0.
