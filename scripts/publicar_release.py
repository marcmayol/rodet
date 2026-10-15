"""Publica una release de Rodet y actualiza el manifiesto de actualizaciones.

Ritual completo (copiado del de Prompter, hermano del de DracPDF, Grimorio y Crónicas):
comprobación de que no hay código sin commitear (si no, el tag de la Release no
contendría lo que se publica), build del APK de release FIRMADO, lectura del versionCode/versionName (fuente única:
gradle.properties), cálculo del sha256, verificación de coherencia (el versionCode
del APK construido, leído con aapt2, debe coincidir con el que se escribirá en el
manifiesto y superar al ya publicado; la firma debe ser la de siempre y nunca la de
debug — si algo no cuadra, aborta), creación de la Release en GitHub con el asset
(gh CLI, verificando antes gh auth status) y publicación de docs/updates.json en
GitHub Pages (commit + push), comprobando después que la URL pública ya sirve el
versionCode nuevo (reintenta, porque la caché del CDN tarda).

Secretos: la firma sale de keystore.properties (en la raíz, gitignored) o de las
variables de entorno RODET_STORE_FILE / RODET_STORE_PASSWORD / RODET_KEY_ALIAS /
RODET_KEY_PASSWORD, que el propio build.gradle.kts ya sabe leer. Si no hay ninguna de
las dos fuentes, aborta con mensaje claro. Ningún secreto se escribe en el repo.

Uso:
    python scripts/publicar_release.py              # construye y publica
    python scripts/publicar_release.py --dry-run    # prepara sin publicar
    python scripts/publicar_release.py --notas "…"  # notas de la versión
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# La consola de Windows viene en cp1252 y se comería los acentos de los mensajes.
for _flujo in (sys.stdout, sys.stderr):
    if hasattr(_flujo, "reconfigure"):
        _flujo.reconfigure(encoding="utf-8", errors="replace")

RAIZ = Path(__file__).resolve().parents[1]
VERSIONES = RAIZ / "gradle.properties"
MANIFIESTO = RAIZ / "docs" / "updates.json"
FIRMA_ESPERADA = RAIZ / "scripts" / "firma_esperada.txt"
APK_RELEASE = RAIZ / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

_REPO = "marcmayol/rodet"
_PAGES_URL = "https://marcmayol.com/rodet/updates.json"
_CHECK_HORAS = 24
_ENV_FIRMA = (
    "RODET_STORE_FILE",
    "RODET_STORE_PASSWORD",
    "RODET_KEY_ALIAS",
    "RODET_KEY_PASSWORD",
)

# DN del debug.keystore que instala el SDK: si el APK sale firmado con esto, la
# keystore de release no se cargó y publicarlo dejaría a todo el mundo sin poder
# actualizar (y a cualquiera capaz de firmar una "actualización" suya).
_DN_DEBUG = "CN=Android Debug"


# --- utilidades ---------------------------------------------------------------

def _ejecutar(cmd: list[str], **kw) -> None:
    print("»", " ".join(cmd))
    if subprocess.call(cmd, cwd=str(RAIZ), **kw) != 0:
        raise SystemExit(f"Falló: {' '.join(cmd)}")


def _salida(cmd: list[str]) -> str:
    return subprocess.run(
        cmd, cwd=str(RAIZ), capture_output=True, text=True
    ).stdout


def sha256(ruta: Path) -> str:
    h = hashlib.sha256()
    with ruta.open("rb") as f:
        for bloque in iter(lambda: f.read(65536), b""):
            h.update(bloque)
    return h.hexdigest()


def _gradlew() -> str:
    return "gradlew.bat" if os.name == "nt" else "./gradlew"


# Los únicos ficheros que el propio script toca: no cuentan como "cambios sueltos".
_ARCHIVOS_DEL_SCRIPT = {"docs/updates.json", "scripts/firma_esperada.txt"}


def asegurar_arbol_limpio() -> None:
    """Aborta si queda código sin commitear.

    El tag de la Release se crea sobre lo que hay en GitHub, así que publicar con
    cambios en el working tree deja un tag que NO contiene el código de esa versión:
    el APK lleva la funcionalidad y el repositorio no. Pasó de verdad con la v2.3.
    """
    pendientes = []
    for linea in _salida(["git", "status", "--porcelain"]).splitlines():
        if not linea.strip():
            continue
        ruta = linea[3:].strip().strip('"')
        if " -> " in ruta:  # renombrados: "origen -> destino"
            ruta = ruta.split(" -> ", 1)[1]
        if ruta.rstrip("/") in _ARCHIVOS_DEL_SCRIPT:
            continue
        pendientes.append(ruta)

    if pendientes:
        lista = "\n".join(f"    {r}" for r in pendientes[:20])
        resto = f"\n    …y {len(pendientes) - 20} más" if len(pendientes) > 20 else ""
        raise SystemExit(
            "Hay cambios sin commitear: la Release quedaría etiquetada sobre un "
            "commit que no contiene este código.\n"
            f"{lista}{resto}\n"
            "  Commitea (o guarda en stash) y vuelve a publicar. Aborto."
        )


# --- versión (fuente única: gradle.properties) ---------------------------------

def leer_version() -> tuple[int, str]:
    texto = VERSIONES.read_text(encoding="utf-8")
    vc = re.search(r"^appVersionCode\s*=\s*(\d+)", texto, re.M)
    vn = re.search(r"^appVersionName\s*=\s*(\S+)", texto, re.M)
    if not vc or not vn:
        raise SystemExit("No se pudo leer appVersionCode/appVersionName de gradle.properties.")
    return int(vc.group(1)), vn.group(1)


# --- firma --------------------------------------------------------------------

def asegurar_firma() -> None:
    """Comprueba que hay credenciales de firma antes de construir.

    No materializa nada: build.gradle.kts ya lee keystore.properties o las variables
    de entorno por sí mismo, así que aquí solo se verifica que exista una de las dos
    fuentes. Así ningún secreto pasa por un fichero temporal.
    """
    props = RAIZ / "keystore.properties"
    if props.exists():
        print("Firma: keystore.properties encontrado.")
        return
    faltan = [k for k in _ENV_FIRMA if not os.environ.get(k)]
    if not faltan:
        print("Firma: usando variables de entorno.")
        return
    raise SystemExit(
        "Faltan credenciales de firma de Rodet.\n"
        "  Opción A: crea keystore.properties en la raíz (está gitignored) con\n"
        "            storeFile, storePassword, keyAlias y keyPassword.\n"
        f"  Opción B: define las variables de entorno: {', '.join(_ENV_FIRMA)}.\n"
        f"  Ahora mismo faltan: {', '.join(faltan)}."
    )


# --- herramientas del SDK (verificación de coherencia) ------------------------

def _sdk_dir() -> Path:
    local = RAIZ / "local.properties"
    if local.exists():
        m = re.search(r"sdk\.dir=(.+)", local.read_text(encoding="utf-8"))
        if m:
            return Path(m.group(1).strip().replace("\\\\", "\\").replace("\\:", ":"))
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            return Path(os.environ[env])
    raise SystemExit("No encuentro el Android SDK (local.properties o ANDROID_HOME).")


def _build_tool(nombre: str) -> Path:
    """Ruta a una herramienta de build-tools, la de versión más alta disponible."""
    exe = f"{nombre}.exe" if os.name == "nt" else nombre
    candidatos = sorted((_sdk_dir() / "build-tools").glob(f"*/{exe}"), reverse=True)
    if candidatos:
        return candidatos[0]
    # apksigner es un .bat en Windows; aapt2 sí es .exe.
    alt = sorted((_sdk_dir() / "build-tools").glob(f"*/{nombre}.bat"), reverse=True)
    if alt:
        return alt[0]
    raise SystemExit(f"No encuentro {nombre} en build-tools del SDK.")


def version_code_del_apk(apk: Path) -> int:
    salida = _salida([str(_build_tool("aapt2")), "dump", "badging", str(apk)])
    m = re.search(r"versionCode='(\d+)'", salida)
    if not m:
        raise SystemExit("No pude leer el versionCode del APK con aapt2.")
    return int(m.group(1))


def _certificados(apk: Path) -> str:
    try:
        return _salida([str(_build_tool("apksigner")), "verify", "--print-certs", str(apk)])
    except SystemExit:
        return ""


def huella_firma(apk: Path) -> str | None:
    """SHA-256 del certificado de firma del APK, o None si apksigner no está."""
    m = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", _certificados(apk))
    return m.group(1).lower() if m else None


def firmado_con_debug(apk: Path) -> bool:
    return _DN_DEBUG.lower() in _certificados(apk).lower()


# --- manifiesto ---------------------------------------------------------------

def url_release(version_name: str) -> str:
    return (
        f"https://github.com/{_REPO}/releases/download/"
        f"v{version_name}/rodet-v{version_name}.apk"
    )


def generar_manifiesto(vc: int, vn: str, sha: str, notas: str) -> dict:
    return {
        "versionCode": vc,
        "versionName": vn,
        "url": url_release(vn),
        "sha256": sha,
        "notas": notas or f"Rodet {vn}.",
        "check_horas": _CHECK_HORAS,
    }


def version_code_publicado() -> int | None:
    """versionCode del último manifiesto COMMITEADO (None si es el primero).

    Se lee de git y no del working tree a propósito: un --dry-run previo ya ha
    reescrito docs/updates.json con la versión que estamos preparando, y compararse
    contra sí misma abortaría siempre.
    """
    ruta = MANIFIESTO.relative_to(RAIZ).as_posix()
    salida = _salida(["git", "show", f"HEAD:{ruta}"])
    if not salida.strip():
        return None
    try:
        return int(json.loads(salida)["versionCode"])
    except Exception:  # noqa: BLE001
        return None


def verificar_coherencia(vc_declarado: int, apk: Path, manifiesto: dict) -> None:
    """Cinturón y tirantes antes de publicar.

    El versionCode del APK construido, el declarado y el del manifiesto coinciden; el
    sha256 del manifiesto es el del APK real; la versión sube respecto a la publicada;
    y la firma es de release y sigue siendo la misma de siempre (si cambia, ninguna
    instalación existente podrá actualizarse).
    """
    vc_apk = version_code_del_apk(apk)
    if vc_apk != vc_declarado:
        raise SystemExit(
            f"El APK construido tiene versionCode {vc_apk}, pero gradle.properties "
            f"declara {vc_declarado}. Aborto."
        )
    if manifiesto["versionCode"] != vc_declarado:
        raise SystemExit("El versionCode del manifiesto no coincide con el declarado.")
    if manifiesto["sha256"] != sha256(apk):
        raise SystemExit("El sha256 del manifiesto no coincide con el APK construido.")

    publicado = version_code_publicado()
    if publicado is not None and vc_declarado <= publicado:
        raise SystemExit(
            f"El versionCode {vc_declarado} no supera al ya publicado ({publicado}): "
            "nadie detectaría la actualización. Sube el versionCode. Aborto."
        )

    if firmado_con_debug(apk):
        raise SystemExit(
            "El APK está firmado con el debug.keystore: la keystore de release no se "
            "cargó. Revisa keystore.properties o las variables de entorno. Aborto."
        )

    huella = huella_firma(apk)
    if huella is None:
        print("Aviso: no pude leer la firma del APK (apksigner no disponible).")
        return
    if FIRMA_ESPERADA.is_file():
        esperada = FIRMA_ESPERADA.read_text(encoding="utf-8").strip().lower()
        if esperada and esperada != huella:
            raise SystemExit(
                "La firma del APK ha cambiado respecto a la de las versiones ya "
                f"distribuidas ({esperada[:16]}… → {huella[:16]}…). Con otra firma, "
                "ninguna instalación existente puede actualizarse. Aborto."
            )
        print(f"Firma verificada: {huella[:16]}…")
    else:
        FIRMA_ESPERADA.write_text(huella + "\n", encoding="utf-8")
        print(f"Firma registrada por primera vez en {FIRMA_ESPERADA.name}: {huella[:16]}…")


# --- construcción -------------------------------------------------------------

def construir() -> Path:
    asegurar_firma()
    _ejecutar([_gradlew(), ":app:assembleRelease"])
    if not APK_RELEASE.is_file():
        raise SystemExit(f"No se generó el APK de release: {APK_RELEASE}")
    return APK_RELEASE


def preparar(notas: str) -> tuple[dict, Path]:
    """Construye, genera y escribe el manifiesto tras verificar coherencia."""
    vc, vn = leer_version()
    apk = construir()
    manifiesto = generar_manifiesto(vc, vn, sha256(apk), notas)
    verificar_coherencia(vc, apk, manifiesto)
    MANIFIESTO.parent.mkdir(parents=True, exist_ok=True)
    MANIFIESTO.write_text(
        json.dumps(manifiesto, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return manifiesto, apk


# --- publicación --------------------------------------------------------------

def _asset_con_nombre(apk: Path, vn: str) -> Path:
    destino = apk.with_name(f"rodet-v{vn}.apk")
    if destino != apk:
        destino.write_bytes(apk.read_bytes())
    return destino


def verificar_gh() -> None:
    if subprocess.call(["gh", "auth", "status"]) != 0:
        raise SystemExit("gh no está autenticado. Ejecuta: gh auth login")


def publicar(apk: Path, manifiesto: dict, notas: str) -> None:
    vn = manifiesto["versionName"]
    asset = _asset_con_nombre(apk, vn)
    _ejecutar([
        "gh", "release", "create", f"v{vn}", str(asset),
        "--repo", _REPO,
        "--title", f"Rodet {vn}",
        "--notes", notas or f"Rodet {vn}.",
    ])
    _ejecutar(["git", "add", str(MANIFIESTO), str(FIRMA_ESPERADA)])
    _ejecutar(["git", "commit", "-m", f"Publica el manifiesto de la v{vn}"])
    _ejecutar(["git", "push", "origin", "main"])


def verificar_url_publica(vc_esperado: int, intentos: int = 30, espera_s: int = 10) -> None:
    """La URL de Pages puede tardar por la caché del CDN: reintenta unos minutos."""
    for i in range(1, intentos + 1):
        try:
            with urllib.request.urlopen(_PAGES_URL, timeout=15) as r:
                data = json.loads(r.read().decode("utf-8"))
            if data.get("versionCode") == vc_esperado:
                print(f"URL pública OK: sirve versionCode {vc_esperado}.")
                return
            print(f"[{i}/{intentos}] Pages sirve {data.get('versionCode')}, esperaba {vc_esperado}…")
        except Exception as e:  # noqa: BLE001
            print(f"[{i}/{intentos}] Aún no disponible ({e.__class__.__name__})…")
        time.sleep(espera_s)
    raise SystemExit(
        "La URL pública no sirvió el versionCode nuevo a tiempo. La Release SÍ se "
        "creó; revisa GitHub Pages (rama/carpeta /docs) y la caché del CDN."
    )


def refrescar_catalogo_dracapps() -> None:
    """La tienda DracApps lee un catálogo estático que se regenera cada 6 h; se lanza ya
    para que salga la versión nueva sin esperar. Si falla, solo avisa: la Release ya está."""
    if subprocess.call(["gh", "workflow", "run", "catalogo.yml", "--repo", "marcmayol/DracApps"]) == 0:
        print("Catálogo de DracApps regenerándose (tarda ~1-2 min).")
    else:
        print("Aviso: no pude lanzar catalogo.yml de DracApps; se actualizará solo en menos de 6 h.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Publica una release de Rodet.")
    parser.add_argument("--dry-run", action="store_true", help="prepara sin publicar")
    parser.add_argument("--notas", default="", help="notas de la versión")
    args = parser.parse_args(argv)

    if not args.dry_run:
        asegurar_arbol_limpio()
        verificar_gh()

    manifiesto, apk = preparar(args.notas)
    print(f"Manifiesto v{manifiesto['versionName']} "
          f"(versionCode {manifiesto['versionCode']}, sha256 {manifiesto['sha256'][:12]}…)")
    print(f"APK: {apk}")

    if args.dry_run:
        print("--dry-run: preparado sin publicar (Release y manifiesto no subidos).")
        return 0

    publicar(apk, manifiesto, args.notas)
    verificar_url_publica(manifiesto["versionCode"])
    print(f"Release v{manifiesto['versionName']} publicada y manifiesto en Pages.")
    refrescar_catalogo_dracapps()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
