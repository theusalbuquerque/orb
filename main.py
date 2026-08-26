from fastapi import FastAPI

app = FastAPI()

@app.get("/")
def get_module_index():
    # Retorna o índice de módulos que o BitChord/Orb consegue ler e validar
    return [
        {
            "id": "qobuz-lossless",
            "name": "Qobuz Lossless Source",
            "version": "1.0.0",
            "minAppVersion": 1,
            "author": "Orb",
            "description": "FLAC via Qobuz",
            "url": "https://orb-4mrh.onrender.com/download",
            "isComplete": True
        }
    ]

@app.get("/download")
def download_module():
    # Aqui posteriormente você entregará o arquivo compilado do módulo (.dex / .zip)
    return {"status": "ready"}
