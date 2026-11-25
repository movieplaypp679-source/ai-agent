# AI Agent - Phase 10 (v2.7)

## Naya kya hai
1. GAME BUILDER: purane action3d/shooter2d templates mein GDScript parse errors the (isi liye game chalta nahi tha) - fix.
   Naye tested templates: battleroyale3d (parachute, loot, 24 bots, shrinking zone, touch HUD, minimap) aur story_adventure (story.json se choices/items/endings).
   Har .gd write ke baad auto LINT (Godot 3 syntax, brackets, JSON). Naya tool gd_lint.
2. UPLOAD: chat mein clip button - image, video (8 frames), text/code, PDF, ZIP. Image/video ke liye vision model auto-select; local model ho to cloud key se route.
3. API ROUTER: credits/daily-limit/rate-limit alag classify, dead provider yaad rakhta hai (Settings > Server mein status), max_tokens cap (OpenRouter 131072 error fix), kharab models (:batch, antigravity, decision) skip.
4. Tool fixes: duplicate call ab cached result deta hai, empty args par usage dikhata hai.

## Free daily-limit APIs (key Settings > Server mein daalo, sab ek saath rotate honge)
Groq, Cerebras, Gemini (aistudio.google.com/apikey), OpenRouter :free, NVIDIA, SambaNova, Mistral.
