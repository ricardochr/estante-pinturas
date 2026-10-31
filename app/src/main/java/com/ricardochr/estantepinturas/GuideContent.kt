package com.ricardochr.estantepinturas

/** Contenido de la guía de técnicas. Texto práctico, en pasos cortos. */
data class GuideArticle(
    val title: String,
    val summary: String,
    val steps: List<String>,
    val tools: String = "",
    val tip: String = "",
)

data class GuideSection(val title: String, val articles: List<GuideArticle>)

val GUIDE: List<GuideSection> = listOf(
    GuideSection(
        "Antes de pintar",
        listOf(
            GuideArticle(
                "Preparar la figura",
                "Una figura limpia y sin líneas de molde hace que todo lo demás se vea mejor.",
                listOf(
                    "Quita las líneas de molde con una lija fina (400–800) o el filo de un cúter pasado de lado, sin cortar.",
                    "Si es de resina o impresión 3D, lávala con agua tibia y jabón para quitar el desmoldante o el residuo de resina; deja secar del todo.",
                    "Pega las piezas antes de imprimar cuando las uniones vayan a quedar visibles; deja separadas las que tapen zonas difíciles (capas, armas grandes).",
                    "Monta la figura en un corcho o un bote con masilla adhesiva para no tocarla mientras pintas.",
                ),
                tools = "Lija fina, cúter, cepillo de dientes viejo, masilla adhesiva.",
            ),
            GuideArticle(
                "Imprimación y zenital",
                "La imprimación hace que la pintura se agarre; la zenital además te muestra dónde caen las luces.",
                listOf(
                    "Imprima toda la figura en negro o gris oscuro, en capas finas a 20–25 PSI (o con spray a 20–30 cm).",
                    "Con el aerógrafo apuntando desde arriba, aplica gris medio en un ángulo de unos 45°.",
                    "Luego aplica blanco solo desde arriba, casi vertical: lo que queda blanco es lo que recibe más luz.",
                    "Toma una foto: es tu mapa de luces y sombras para el resto de la figura.",
                ),
                tools = "Aerógrafo o spray, imprimación negra y blanca.",
                tip = "Sobre una zenital puedes aplicar pinturas de contraste o veladuras y el sombreado ya queda hecho.",
            ),
            GuideArticle(
                "Diluir la pintura",
                "La regla de las capas finas: varias capas diluidas cubren mejor y no tapan los detalles.",
                listOf(
                    "Pon una gota de pintura en la paleta y agrega agua poco a poco.",
                    "Para pincel, busca consistencia de leche entera: fluye del pincel pero no forma charcos.",
                    "Si al pintar se ven marcas de pincel, está espesa; si se escurre a los huecos, está muy diluida.",
                    "Da 2–3 capas dejando secar entre una y otra.",
                ),
                tip = "Mira la sección Diluciones de cada pintura para proporciones de aerógrafo, veladura y lavado.",
            ),
            GuideArticle(
                "Paleta húmeda",
                "Mantiene la pintura fresca por horas: imprescindible para capas y transiciones.",
                listOf(
                    "Pon una esponja o servilletas mojadas en un recipiente plano.",
                    "Encima coloca papel encerado o de horno (papel para paleta húmeda).",
                    "La pintura absorbe agua del fondo y no se seca; tápala al terminar y dura días.",
                ),
                tools = "Recipiente con tapa, esponja, papel de horno.",
            ),
        ),
    ),
    GuideSection(
        "Luz y sombra",
        listOf(
            GuideArticle(
                "Dónde va la luz",
                "Si no sabes dónde poner luces y sombras, imagina una lámpara justo encima de la figura.",
                listOf(
                    "Todo lo que mira hacia arriba recibe luz: hombros, parte superior de la cabeza, nariz, rodillas, bordes superiores de pliegues.",
                    "Todo lo que mira hacia abajo o está metido queda en sombra: debajo de la barbilla, axilas, entre dedos, interior de pliegues.",
                    "Los bordes que sobresalen (filos de armadura, nudillos) llevan la luz más fuerte, en línea fina.",
                    "La cara es lo que más se mira: ahí pon el mayor contraste.",
                ),
                tip = "La imprimación zenital te da este mapa sin tener que imaginarlo.",
            ),
            GuideArticle(
                "Sombreado 2D estilo anime (cel shading)",
                "Luz y sombra con bordes definidos, sin degradados, como en un anime.",
                listOf(
                    "Pinta el color base plano y parejo en toda la zona.",
                    "Decide una sola dirección de luz y mantenla en toda la figura.",
                    "Pinta la sombra con un tono más oscuro y algo más saturado o frío (no solo base con negro), con borde nítido. Sigue la forma: debajo del pelo, en el cuello, en pliegues.",
                    "Opcional: una segunda sombra más oscura solo en los huecos más profundos.",
                    "Agrega la luz como una forma limpia en las zonas más altas; en el pelo, una franja o 'banda de brillo' que siga la curva del mechón.",
                    "Remata con brillos puntuales en blanco o casi blanco (ojos, labios, metal) y, si quieres, delineado oscuro en los bordes de las formas.",
                ),
                tools = "Pincel redondo talla 1–2 para formas, 0/00 para bordes y brillos.",
                tip = "Usa la pestaña Paleta: te sugiere la pintura de sombra y de luz para cada color de tu referencia.",
            ),
            GuideArticle(
                "Capas (layering)",
                "Construir luces con capas sucesivas, cada una más clara y más pequeña.",
                listOf(
                    "Pinta el color base.",
                    "Mezcla base con un poco de color claro; aplícalo cubriendo un 70% de la zona, dejando ver la base en los huecos.",
                    "Aclara otra vez y pinta un área menor, hacia donde da la luz.",
                    "Repite 3–5 veces; la última capa son solo puntos o líneas finas.",
                    "Si los saltos se notan, pasa una veladura del tono intermedio para unirlos.",
                ),
                tools = "Pincel redondo talla 1–2 con buena punta, paleta húmeda.",
            ),
            GuideArticle(
                "Veladuras (glazing)",
                "Capas casi transparentes para suavizar transiciones o cambiar un poco el tono.",
                listOf(
                    "Diluye mucho la pintura, idealmente con medio de veladura (ver Diluciones).",
                    "Descarga el pincel en una servilleta: debe quedar húmedo, no goteando.",
                    "Pasa la veladura sobre la zona de transición; deja secar y repite si hace falta.",
                    "Para dar calidez a la piel, una veladura roja o morada muy diluida en las sombras funciona muy bien.",
                ),
                tip = "Si se forman 'anillos' al secar, está demasiado aguada o llevas mucha pintura en el pincel.",
            ),
            GuideArticle(
                "Fusión húmeda (wet blending)",
                "Mezclar dos colores sobre la figura mientras están frescos para un degradado suave.",
                listOf(
                    "Prepara los dos colores en la paleta húmeda, un poco más espesos que de costumbre.",
                    "Pinta el color oscuro en una mitad y el claro en la otra, rápido.",
                    "Con el pincel apenas húmedo, frota suavemente la unión hasta fundirla.",
                    "Trabaja zonas pequeñas: la pintura acrílica seca en segundos.",
                ),
                tools = "Retardante (unas gotas en la mezcla) y pincel talla 2.",
            ),
            GuideArticle(
                "Lavados (washes)",
                "Pintura muy diluida que corre a los huecos y marca sombras y detalles.",
                listOf(
                    "Aplica el lavado con un pincel cargado sobre la zona ya pintada.",
                    "Retira el exceso de las zonas planas con el pincel limpio y seco para que no queden manchas.",
                    "Deja secar del todo antes de seguir.",
                    "Para lavar solo los huecos (pin wash), usa un pincel fino y toca solo las uniones.",
                ),
            ),
            GuideArticle(
                "Pincel seco (drybrush)",
                "Rápido para resaltar texturas: pelaje, roca, cota de malla, las burbujas de una base.",
                listOf(
                    "Toma un poco de pintura clara con un pincel viejo de cerdas firmes.",
                    "Descárgalo en una servilleta hasta que casi no deje marca.",
                    "Frota con movimientos rápidos sobre los relieves: la pintura queda solo en las partes salientes.",
                    "Repite con un tono más claro todavía, con menos presión.",
                ),
                tools = "Pincel de drybrush o maquillaje redondo, servilleta.",
            ),
            GuideArticle(
                "Pinturas de contraste (Speedpaint, Xpress, Contrast)",
                "Base, sombra y luz en una sola capa.",
                listOf(
                    "Imprima en blanco, gris claro o zenital.",
                    "Carga bien el pincel y aplica una capa generosa, sin repasar mientras se seca.",
                    "La pintura se acumula sola en los huecos (sombra) y queda clara en las partes altas.",
                    "Remata con una luz de pincel en los bordes para dar más acabado.",
                ),
                tip = "No la diluyas con agua: usa su medio propio para aclararla.",
            ),
        ),
    ),
    GuideSection(
        "Efectos",
        listOf(
            GuideArticle(
                "Ojos",
                "Lo que más vida le da a una figura.",
                listOf(
                    "Pinta el blanco del ojo en un hueso o crema, no blanco puro.",
                    "Haz el iris como una línea o punto vertical en el centro; mira la figura de frente para que ambos ojos miren al mismo lado.",
                    "Contornea el ojo con un café oscuro para limpiar errores.",
                    "Un punto de brillo blanco en el iris, del lado de la luz.",
                ),
                tools = "Pincel talla 00 o 000 con buena punta, poca pintura, mano apoyada.",
            ),
            GuideArticle(
                "Piel",
                "Tres tonos bastan para una piel creíble.",
                listOf(
                    "Base de tono medio (carne).",
                    "Sombra con la base mezclada con un café rojizo o morado; aplícala en cuencas de los ojos, debajo de la nariz y la barbilla, cuello.",
                    "Luces mezclando la base con hueso o blanco cálido: frente, nariz, pómulos, barbilla.",
                    "Una veladura rojiza muy diluida en mejillas, nariz y nudillos da vida.",
                ),
            ),
            GuideArticle(
                "Metal no metálico (NMM)",
                "Simular metal brillante con pintura normal, a base de contraste extremo.",
                listOf(
                    "Para acero: de negro azulado a gris y a blanco puro; para oro: de café oscuro a ocre, amarillo y casi blanco.",
                    "Coloca reflejos marcados: el metal pulido refleja el 'cielo' (claro) arriba y el 'suelo' (oscuro) abajo, con un corte brusco en medio.",
                    "Termina con puntos de brillo blanco en las esquinas que miran a la luz.",
                ),
            ),
            GuideArticle(
                "Luz de objeto (OSL)",
                "Simular que algo brilla: una espada mágica, una pantalla, fuego.",
                listOf(
                    "Pinta la figura un poco más oscura de lo normal para que el brillo destaque.",
                    "Pinta la fuente de luz casi blanca en el centro y saturada en los bordes.",
                    "Con veladuras del color de la luz, tiñe las superficies cercanas que miran hacia la fuente.",
                    "La intensidad baja rápido con la distancia: solo lo cercano se ilumina.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Aerógrafo",
        listOf(
            GuideArticle(
                "Lo básico",
                "Dilución, presión y distancia van juntas.",
                listOf(
                    "Pintura con consistencia de leche descremada (ver Diluciones de cada pintura).",
                    "15–20 PSI para detalle y degradados; 25–30 PSI para cubrir o imprimar.",
                    "A 5–10 cm para cubrir; a 1–3 cm con baja presión para líneas finas.",
                    "Siempre empieza a echar aire antes de abrir la pintura y corta la pintura antes que el aire.",
                    "Prueba en papel o en tu mano con guante antes de ir a la figura.",
                ),
                tools = "Boquilla 0.3 mm sirve para casi todo; 0.2 mm para detalle.",
            ),
            GuideArticle(
                "Problemas comunes",
                "Cómo leer lo que pasa.",
                listOf(
                    "Arañas (salpicaduras con patitas): demasiada pintura, demasiado cerca o muy diluida. Aléjate o baja la presión.",
                    "Textura granulada o polvosa: pintura muy espesa o te alejaste mucho; diluye o acércate.",
                    "Punta seca (sale a ratos): pintura secándose en la boquilla; limpia la punta con un pincel húmedo y agrega retardante o flow improver.",
                    "Burbujas en el depósito: algo está flojo o tapado; revisa la boquilla y el tapón.",
                ),
            ),
            GuideArticle(
                "Limpieza",
                "Un aerógrafo limpio funciona bien siempre.",
                listOf(
                    "Entre colores: vacía el depósito, pon agua o limpiador, frota con un pincel y rocía hasta que salga limpio.",
                    "Al terminar: haz retroflujo (tapa la salida con un dedo o trapo y aprieta), luego rocía limpiador.",
                    "Cada tanto desarma aguja y boquilla y límpialas con limpiador; la aguja se dobla con nada, manéjala con cuidado.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Color",
        listOf(
            GuideArticle(
                "Sombras con más vida",
                "Oscurecer solo con negro deja los colores sucios.",
                listOf(
                    "Para sombrear, mezcla la base con un tono más oscuro y frío (azul, morado) o con su complementario en poca cantidad.",
                    "Para iluminar, mezcla con un tono claro y cálido (hueso, amarillo pálido) en vez de solo blanco, que enfría y apaga el color.",
                    "Rojo se sombrea con morado o café rojizo; amarillo con ocre o café verdoso; azul con azul marino o morado.",
                ),
            ),
            GuideArticle(
                "Mezclar colores",
                "Cómo usar las sugerencias de mezcla de la app.",
                listOf(
                    "Las proporciones son en gotas: '2 gotas de A + 1 gota de B'.",
                    "Mezcla primero en pequeño, prueba en la paleta y ajusta a ojo: la fuerza de cada marca varía.",
                    "Los blancos aclaran mucho y los negros oscurecen mucho: agrégalos de a poco.",
                    "Si vas a necesitar mucha cantidad, mezcla de más y guarda el sobrante en un bote vacío.",
                ),
            ),
        ),
    ),
    GuideSection(
        "Pinceles y acabado",
        listOf(
            GuideArticle(
                "Qué pincel usar",
                "Pocas tallas bien cuidadas rinden más que muchas.",
                listOf(
                    "Talla 2–4 redondo o plano: bases y zonas grandes.",
                    "Talla 1: capas, veladuras y la mayor parte del trabajo.",
                    "Talla 0 / 00: detalles, ojos, bordes; lo importante es la punta, no el tamaño.",
                    "Pincel viejo o de maquillaje: pincel seco.",
                    "Un pincel de pelo natural (marta kolinsky) mantiene la punta mucho más tiempo.",
                ),
            ),
            GuideArticle(
                "Cuidar los pinceles",
                "La pintura seca en la base de las cerdas arruina la punta.",
                listOf(
                    "No cargues pintura más allá de la mitad de las cerdas.",
                    "Enjuaga seguido mientras pintas; nunca dejes el pincel parado de punta en el agua.",
                    "Al terminar lávalo con jabón para pinceles, dale forma de punta y guárdalo acostado o con la punta hacia arriba.",
                ),
            ),
            GuideArticle(
                "Barniz",
                "Protege la pintura y unifica el acabado.",
                listOf(
                    "Espera al menos 24 horas después de terminar.",
                    "Barniz mate en capas finas (aerógrafo o spray a 25–30 cm) en un día seco.",
                    "Después, barniz brillante solo donde debe brillar: ojos, labios, gemas, metal pulido, sangre fresca.",
                ),
            ),
        ),
    ),
)

/** Tabla general de diluciones para la guía. */
val DILUTION_TABLE: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "Aerógrafo" to listOf(
        "Lista para aerógrafo (Model Air, Warpaints Air, Kaleido, DSPIAE)" to "Directa · hasta 4:1",
        "Pincel estándar (Model Color, Game Color, Warpaints, AK)" to "1:1",
        "Muy pigmentada (Pro Acryl, Scalecolor, Citadel Base)" to "1:1 a 1:2",
        "Metálica" to "1:1",
        "Tinta" to "1:1",
        "Contraste / Speedpaint" to "Directa o 2:1 con su medio",
    ),
    "Veladura (con medio de veladura)" to listOf(
        "Pincel estándar" to "1:3 a 1:5",
        "Muy pigmentada" to "1:4 a 1:6",
        "Tinta" to "1:3 a 1:6",
        "Contraste" to "1:1 a 1:3 con su medio",
    ),
    "Lavado (con medio de lavado)" to listOf(
        "Pincel estándar" to "1:5 a 1:8",
        "Muy pigmentada" to "1:6 a 1:10",
        "Tinta" to "1:4 a 1:8",
        "Wash comercial" to "Directo",
    ),
)
