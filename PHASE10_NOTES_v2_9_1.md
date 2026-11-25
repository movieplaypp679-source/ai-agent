# AI Agent - v2.9.1 patch (no-template games + stability)

## 1. Koi fixed game template nahi (sabse bada fix)
- `godot_new` tool ab sirf ek KHAALI Godot 4 scaffold banata hai (project.godot, icon,
  main scene). Saare 9 pehle-se-bane games (platformer2d, runner2d, battleroyale2d/3d,
  action3d, story_adventure, etc.) ab kabhi use nahi hote.
- AI ko ab har baar apna poora, is-exact-game ke liye customized `main.gd` khud likhna
  padta hai (`main_gd` parameter), tabhi game bada hai — koi do games ab ek jaisi copy
  nahi niklenge.
- System prompt (RULES, GAME_BRIEF, game_maker skill) se saari "use template X for genre
  Y" instructions hata di gayi hain.
- Agar game ka core idea/mechanic clear na ho, AI ab seedha guess karke nahi banayega —
  pehle EK chhota, specific sawal poochhega. Baaki chhoti cheezon ke liye phir bhi
  khud decide karega (bar-bar nahi poochega).
- Default platform mobile hai: touch controls ab har game ki apni mechanics ke hisaab se
  design hote hain (joystick/tap/drag/buttons), fixed ek jaisa layout nahi. PC ke liye
  keyboard/mouse bhi hamesha saath rehta hai.

## 2. Garbled / mixed-language output bug
Screenshot mein jo random Chinese/Arabic/Tamil/Kannada mix wali gibberish dikhi, uska
sabse common reason hota hai: system prompt (44 tools + rules + skills list) model ke
context window se bada ho jaana, jisse chhote/quantized model (jaise on-device
Nemotron 12B VL) "overflow" ho kar garbage generate karne lagta hai.
- `devSel().ctx<=2048` threshold ko `8192` kar diya — ab zyada models ke liye app
  automatically chhota, trimmed prompt (sirf zaroori tools, bina RULES/skills text ke)
  bhejega, jisse overflow kam hoga.
- Default creativity (temperature) 0.7 se 0.45 kar di — isse weak/chhote models ke
  "derail" ho kar bakwaas likhne ka chance kaafi kam ho jaata hai.

### Agar phir bhi gibberish aaye:
- Settings > Creativity slider ko aur neeche (0.2-0.3) kar ke dekho.
- Settings > Tools mein jo tools use nahi karte unhe band kar do (kam tools = chhota
  prompt = kam overflow risk).
- Agar possible ho to koi bada-context, text-only model try karo (jaise Groq/Cerebras
  free tier ka Llama 3.3 70B) agentic/tool-heavy kaam ke liye — chhote VL (vision)
  models lambe tool-prompts ko utna reliably handle nahi karte.
