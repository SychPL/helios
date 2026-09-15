# HELIOS — pomiar mikrofonu w tle, 2026-09-15

## Wynik: POTWIERDZONE w zakresie krótkiego odbioru sygnału w tle

Wykonano ograniczoną do 30 sekund próbę przez istniejący agent LAN. Użytkownik zgłosił trwające spotkanie i wiele osób rozmawiających przy zegarze. Nie było kontrolowanego bodźca ani oznaczonej ciszy. Nie zapisano ani nie przesłano PCM; zapisywano wyłącznie statystyki. Nie wykonano transkrypcji.

- APK zgłaszana przez agent: `pl.mateusz.clockadbprobe v2.13`; UID 10058, SDK 29. Lokalny HEAD: `61b0808`. Nie zweryfikowano hasha zainstalowanej APK względem commita.
- Tester: `BackgroundMicProbe.java`, załadowany jako DEX, bez wymiany APK. SHA256 DEX: `498b970cd3c5845d64e73b9bf336cb5218d4c72cffeb802b66ad4fc4f1e4e945`.
- Parametry odczytane z AudioRecord: source 7, 16000 Hz, 1 kanał, encoding 2 (PCM16).
- 29 zapisanych okien około sekundowych w pętli ograniczonej do 30 sekund; ostatnie niepełne okno nie jest emitowane przez tester.
- RMS 5.23–66.82, peak maksymalny 361, próbki niezerowe 90.29–99.05%.
- We wszystkich zapisanych oknach: `importance=125`, `interactive=true`, `silenced=false`, ustawienie brightness=235.
- `residentService=true` przed i po teście. `RESULT=COMPLETED`, `RELEASED`; bez zgłoszonych błędów odczytu.

`importance=125` oznacza proces z usługą pierwszoplanową, odrębny od kategorii procesu z interfejsem na pierwszym planie: [Android ActivityManager](https://developer.android.com/reference/android/app/ActivityManager.RunningAppProcessInfo). Odczyt `silenced=false` oznacza brak raportowanego wyciszenia naszego klienta przez politykę współbieżnego przechwytywania: [Android AudioRecordingConfiguration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration). Nie dowodzi to dostępu Google do audio.

## Ograniczenia i dalsze próby

- POTWIERDZONE: zmienny, niezerowy sygnał w procesie z usługą pierwszoplanową, bez własnego UI na pierwszym planie według importance.
- NIESPRAWDZONE: jaka dokładnie aplikacja zajmowała ekran. Accessibility było odłączone; nie wykonano obrazu ekranu. Nie należy opisywać tego jako wizualnie potwierdzonego fabrycznego zegara.
- NIESPRAWDZONE: związek zmian sygnału z konkretną wypowiedzią, zrozumiałość, dystans, STT, HA, TTS, wake word.
- NIESPRAWDZONE: ekran przyciemniony i fizycznie wyłączony. `interactive=true` i ustawienie jasności nie są pomiarem luminancji panelu.
- NIESPRAWDZONE: Google przed/podczas/po; nie wywoływano asystenta na spotkaniu.
- NIESPRAWDZONE: stabilność wielogodzinna i powrót po przerwaniu strumienia.
- SSH: połączenie z portem 2222 odrzucone. Agent 8555 działa; nie zmieniano usług SSH, HOME ani pakietów systemowych.

Najmniejszy kolejny test: po spotkaniu oznaczona cisza i zdanie przy zegarze, następnie sekwencja Google przed/podczas/po z tym samym źródłem audio.

Dowód: `measurement.txt`. Kod i skrypt uruchomienia znajdują się w tym samym katalogu. Tymczasowy serwer podający wyłącznie DEX został zamknięty, mikrofon zwolniony.
