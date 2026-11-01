# Архитектура Google Antigravity Mobile (2026)

1. **Ядро модели**: Используется модель Gemini 4 Argon High (gemini-4-argon-high) с глубоким рассуждением на русском языке.
2. **Двухрежимный агентный цикл**:
   - Режим сессии ПК (PC_REMOTE_SESSION): прямое продолжение сессии через gy --conversation <id> с обновлением 	ranscript.jsonl.
   - Режим памяти телефона (PHONE_LOCAL_RESOURCES): многошаговый агентный цикл (до 6 шагов) с локальными инструментами search_files, search_media, ead_file, write_file, patch_file.
3. **Голосовой режим Live Voice**: Потоковый синтез речи по предложениям (/api/tts) и мгновенное перебивание голосом (Barge-In).