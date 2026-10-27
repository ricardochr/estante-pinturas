# Estante de Pinturas

App Android para llevar el inventario de pinturas de miniaturas.

- **Catálogo incluido** con 7,400 pinturas: Vallejo, Pro Acryl, Citadel, The Army Painter, AK, Scale75, Reaper, Green Stuff World y más. Cada una trae su código y un color aproximado.
- **Escáner sin internet** con ML Kit. Reconoce códigos impresos (70.955, MPA-040, WP1401), nombres y códigos de barras. Los de Vallejo se reconocen solos. Un código de barras de otra marca se asocia una vez y desde ahí queda guardado.
- **Búsqueda** por nombre, código, marca, tipo o familia de color ("piel", "rojo", "metálica").
- **Importar y exportar CSV**, compatible con la versión web.
- No usa créditos de Claude ni necesita cuenta. El inventario vive en el teléfono.

## Obtener el APK

Cada push a `main` compila el APK con GitHub Actions y lo publica en **Releases**. Desde el teléfono:
abre la página de Releases del repo → descarga `EstantePinturas-N.apk` → ábrelo → permite "instalar apps de orígenes desconocidos".
Las versiones nuevas se instalan encima y conservan el inventario, porque todas se firman con la misma llave (`app/estante.jks`).

## Compilar en tu PC

Abre la carpeta en Android Studio y usa *Build → Build APK(s)*, o desde la terminal ejecuta `./gradlew assembleRelease`.

## Actualizar el catálogo

```
git clone https://github.com/Arcturus5404/miniature-paints
python3 tools/build_catalog.py miniature-paints/paints app/src/main/assets/catalog.json
```

Datos del catálogo: [miniature-paints](https://github.com/Arcturus5404/miniature-paints) de Rick Fleuren, licencia MIT (ver `tools/miniature-paints-LICENSE.txt`).
