from fastapi import FastAPI
import httpx
import hashlib
import time

app = FastAPI()

# Seu token extraído do Qobuz
QOBUZ_TOKEN = "92Vjz7KkXOgtBdc795H0rTccz9Tt53FbF5ejamZ43tlEvPzz4097JIodYFGbWzHhGlzHdJkaMYQ4oHLJQok_Ng"
APP_ID = "243542385" # App ID público comum do webplayer da Qobuz

@app.get("/")
def get_module_index():
    return [{
        "id": "qobuz-lossless",
        "name": "Qobuz Lossless Source",
        "version": "1.0.0",
        "minAppVersion": 1,
        "author": "Orb",
        "description": "FLAC via Qobuz",
        "url": "https://orb-4mrh.onrender.com/resolve",
        "isComplete": True
    }]

@app.get("/resolve")
async def resolve_track(query: str):
    headers = {
        "X-User-Auth-Token": QOBUZ_TOKEN,
        "X-App-Id": APP_ID,
        "User-Agent": "Mozilla/5.0"
    }
    
    async with httpx.AsyncClient() as client:
        # 1. Busca a faixa na Qobuz
        search_res = await client.get(
            f"https://www.qobuz.com/api.json/0.2/catalog/search",
            params={"query": query, "type": "tracks", "limit": 1},
            headers=headers
        )
        
        data = search_res.json()
        tracks = data.get("tracks", {}).get("items", [])
        
        if not tracks:
            return {"url": "", "format": "FLAC"}
            
        track_id = tracks[0]["id"]
        
        # 2. Pede a URL de streaming em FLAC (Format ID 6)
        # Nota: A Qobuz exige assinatura MD5 dependendo da versão do App ID.
        stream_res = await client.get(
            f"https://www.qobuz.com/api.json/0.2/track/getFileUrl",
            params={"track_id": track_id, "format_id": 6},
            headers=headers
        )
        
        stream_data = stream_res.json()
        audio_url = stream_data.get("url")
        
        return {
            "url": audio_url,
            "format": "FLAC"
        }
