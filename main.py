from fastapi import FastAPI
import httpx

app = FastAPI()

# Seu token da Qobuz
QOBUZ_TOKEN = "92Vjz7KkXOgtBdc795H0rTccz9Tt53FbF5ejamZ43tlEvPzz4097JIodYFGbWzHhGlzHdJkaMYQ4oHLJQok_Ng"

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
    # O token já está disponível para ser usado nas chamadas da API da Qobuz
    return {
        "url": "LINK_DIRETO_DO_FLAC",
        "format": "FLAC"
    }
