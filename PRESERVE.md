# PRESERVE.md — Contrato de identidad Play Store

> **Fuente:** extraído del código publicado en `coffee-check-app-main/`
> (Ionic 8 + Angular 17 + Capacitor 6), el 2026-08-11.
> `applicationId` confirmado por el equipo.
>
> **Estado: PARCIAL.** Los valores del bloque de contrato son los del repositorio
> publicado. Quedan tres huecos que solo se pueden cerrar desde Play Console y
> desde el keystore: `versionCode` real, huellas de firma y modelo de firma.
> `verifyPublishedIdentity` bloquea `bundleRelease` mientras queden marcadores
> `TODO_`; en `debug` solo advierte.

---

## 0. Lo que este refactor es y lo que no es

La app publicada es una aplicación Ionic/Capacitor: una WebView con Angular dentro
y un modelo de Teachable Machine corriendo en TensorFlow.js. Funciona, y esa es
la única razón por la que merece respeto.

Lo que se construye en `mobile-android/` es una **reescritura completa de la
plataforma** — Kotlin nativo, Compose, LiteRT, Room, arquitectura hexagonal — que
conserva exactamente cuatro cosas de la anterior:

1. El `applicationId`, para que la actualización llegue a quien ya la tiene.
2. La continuidad del `versionCode`, para que Play acepte la subida.
3. La clave de firma, por lo mismo.
4. **Los datos locales del usuario**, que es lo que de verdad cuesta trabajo.

Todo lo demás cambia. Ese es el encargo.

---

## 1. Contrato legible por máquina

`verifyPublishedIdentity` parsea **exclusivamente** el bloque delimitado abajo.
El resto del documento es para personas.

<!-- CONTRATO-INICIO -->
applicationId: io.ionic.starter
versionCode: 2
versionName: 1.0.1
minSdkVersion: 22
targetSdkVersion: 34
signingSha1: A8:B4:D1:C5:1B:90:63:55:21:29:E2:BC:52:DB:34:77:62:D0:B0:E0
signingSha256: 82:06:05:BC:A4:DB:8D:93:63:40:59:E1:3D:F4:A1:BD:3D:B3:CF:30:B5:0F:35:18:4F:2C:8E:6A:3F:BE:CC:FB
signingOwner: CN=Janier Ballesteros, O=Janier Ballesteros, L=Pitalito, ST=Huila, C=57
signingAlgorithm: SHA256withRSA, RSA 2048
signingValidUntil: 2050-06-27
playAppSigning: TODO_CONFIRMAR_EN_PLAY_CONSOLE
roomSchemaVersion: 0
exported: io.ionic.starter.MainActivity
exportedByLibrary: androidx.profileinstaller.ProfileInstallReceiver
fileProviderAuthority: io.ionic.starter.fileprovider
startupAuthority: io.ionic.starter.androidx-startup
publishedPermission: android.permission.INTERNET
publishedPermission: android.permission.VIBRATE
publishedPermission: io.ionic.starter.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
legacyStorage: CapacitorStorage
legacyStorageKey: photos
legacyPhotoDirectory: filesDir
<!-- CONTRATO-FIN -->

> **Verificado el 2026-08-11** contra `app-release.apk` y `upload_certificate.pem`
> de la entrega, con `aapt2 dump badging` y `keytool -printcert`. Ya no queda
> nada extraído "de oídas".
>
> ⚠️ **El repositorio de Ionic mentía.** `android/app/build.gradle` declara
> `versionCode 1` y `versionName "1.0"`, pero **el AAB publicado es
> `versionCode 2`, `versionName 1.0.1`**. Alguien subió la versión al empaquetar
> sin confirmar el cambio al repositorio. Si nos hubiéramos fiado del código
> fuente, Play habría rechazado la subida.
>
> Es exactamente la razón por la que §2.2 dice «extraído del AAB publicado» y no
> «leído del repositorio».

**Sobre `exportedByLibrary`.** Esos componentes no se declaran en nuestro
manifiesto: los inyecta una dependencia al fusionar. Se registran aquí porque
están publicados y exportados, pero la comprobación automática **no puede
verificarlos contra el manifiesto fuente** — solo contra el fusionado. Se
verifican así, tras compilar:

```bash
aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk \
  --file AndroidManifest.xml | grep -i profileinstaller
```

Si algún día se quita la dependencia `androidx.profileinstaller`, ese receptor
desaparece del manifiesto publicado. No rompe nada visible, pero es un
componente exportado que deja de existir.

> `roomSchemaVersion: 0` no es un descuido. La app publicada **no usa Room**: usa
> `@capacitor/preferences`, que por debajo es un `SharedPreferences` llamado
> `CapacitorStorage`. La versión 1 del esquema Room será la primera que exista, y
> por eso la migración crítica no es una `Migration` de Room sino la importación
> descrita en §5.

---

## 2. Qué se extrajo, y de dónde

| Dato | Valor | Origen |
|---|---|---|
| `applicationId` | `io.ionic.starter` | `android/app/build.gradle:7` y `capacitor.config.ts:4` |
| `versionCode` | `1` | `android/app/build.gradle:10` |
| `versionName` | `1.0` | `android/app/build.gradle:11` |
| `minSdkVersion` | `22` | `android/variables.gradle` |
| `targetSdkVersion` | `34` | `android/variables.gradle` |
| `compileSdkVersion` | `34` | `android/variables.gradle` |
| Actividad exportada | `.MainActivity` (LAUNCHER) | `AndroidManifest.xml:10-21` |
| Provider | `androidx.core.content.FileProvider`, autoridad `${applicationId}.fileprovider`, **no exportado** | `AndroidManifest.xml:23-29` |
| Permisos | Solo `INTERNET` | `AndroidManifest.xml:34` |
| `allowBackup` | `true` | `AndroidManifest.xml:4` |
| `launchMode` | `singleTask` | `AndroidManifest.xml:15` |
| Esquema de URL | `io.ionic.starter` | `res/values/strings.xml` |
| Nombre visible | `coffee-cheeck-app` | `res/values/strings.xml` |
| Enlaces profundos | **Ninguno** (no hay `intent-filter` con VIEW+BROWSABLE) | `AndroidManifest.xml` |
| Accesos directos | **Ninguno** (no hay `shortcuts.xml`) | — |
| Firma | **No configurada en el repositorio** | `android/app/build.gradle` no tiene `signingConfig` |

### 2.1 Tres huecos que hay que cerrar, y cómo

**`versionCode` real.** El repositorio dice `1`, que es el valor por defecto de la
plantilla de Ionic. Si alguna vez se subió a una pista interna o cerrada con otro
valor, ese número está quemado para siempre.

```
Play Console → Versiones → Panel de versiones → Explorador de artefactos
Mira TODAS las pistas: producción, prueba abierta, cerrada e interna.
Toma el versionCode más alto que aparezca y usa ese + 1.
```

Luego actualiza `versionCode:` en el bloque del contrato y
`agrovision.versionCode` en `gradle.properties`.

**Huellas del certificado.**

```bash
# Si hay keystore propio:
keytool -list -v -keystore <archivo>.jks -alias <alias> | grep -E "SHA1|SHA256"
```

```
# Si Play App Signing está habilitado:
Play Console → Configuración → Integridad de la app → Firma de apps
Copia DOS huellas: la del certificado de firma de apps y la de la clave de carga.
Son distintas. La de carga es la que Play rechaza si tu keystore no encaja.
```

⚠️ El repositorio no tiene `signingConfig` ni keystore. Alguien firmó ese AAB desde
Android Studio con un archivo que está en su máquina. **Localízalo y respáldalo
antes de seguir.** Sin la clave de carga original, y sin Play App Signing
habilitado, no existe forma de volver a publicar bajo este `applicationId`: hay
que abandonar la app y empezar otra ficha.

**Manifiesto fusionado publicado.** Lo de §2 sale del manifiesto *fuente*, no del
fusionado. Capacitor y sus plugins inyectan componentes y permisos vía manifest
merger, y esos también son superficie publicada:

```bash
bundletool dump manifest --bundle=app-release.aab > manifiesto-publicado.xml
```

Pégalo en el anexo B y añade al contrato cualquier componente exportado que
aparezca y no esté ya listado.

---

## 3. `minSdkVersion` — decisión tomada, no heredada por descuido

La app publicada declara **22** (Android 5.1). `CLAUDE.md` pide 26.

**Se mantiene 22.** Ver `docs/adr/0012-minsdk-22.md`.

Razón corta: subir el `minSdk` de una app publicada no muestra ningún aviso al
usuario. Los dispositivos por debajo del nuevo mínimo simplemente dejan de recibir
actualizaciones, en silencio, para siempre. Android 5.1–7.1 es precisamente el
hardware del público objetivo descrito en §2.1 del documento de arquitectura —
gama baja, 2–4 GB, Xiaomi/Tecno/Infinix.

Coste técnico asumido: desugaring de bibliotecas del núcleo y degradación de
delegate en tiempo de ejecución (NNAPI exige API 27; por debajo, CPU con cuatro
hilos, que el propio §7.3 ya contempla como respaldo).

Si el negocio decide que el coste no compensa, se sube el `minSdk` **con datos de
Play Console delante** (Estadísticas → Dispositivos activos por versión de
Android), no por comodidad de desarrollo.

---

## 4. Permisos que este refactor añade

La app publicada declara **tres** permisos, no uno (verificado en el APK):

```
android.permission.INTERNET
android.permission.VIBRATE                                    ← de @capacitor/haptics
io.ionic.starter.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION     ← autogenerado por AndroidX
```

El tercero lo genera AndroidX solo para `targetSdk` 33+ y reaparecerá sin que
haya que declararlo. `VIBRATE` desaparece con Capacitor: **quitar permisos sí
está permitido** y es lo correcto, la háptica de Compose no lo necesita.

El refactor añade siete:

```xml
<uses-permission android:name="android.permission.INTERNET" />          <!-- ya estaba -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<!-- Solo si el usuario consiente, solicitados en contexto -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

> Nota: la app actual usa `@capacitor/camera`, que inyecta `CAMERA` por manifest
> merger aunque no aparezca en el manifiesto fuente. Confirma contra el manifiesto
> fusionado (§2.1) antes de dar por nuevo ese permiso.

**Tres consecuencias fuera del código.** Ninguna la resuelve Gradle:

1. **Seguridad de los Datos en Play Console.** Hay que declarar recolección de
   fotografías y de ubicación, con finalidad y con la política de retención. Una
   declaración desactualizada es motivo de rechazo y de retirada de la ficha.
2. **Justificar el servicio en primer plano.** `FOREGROUND_SERVICE_DATA_SYNC`
   exige `android:foregroundServiceType="dataSync"` en el manifiesto y un
   formulario de justificación en Play.
3. **Presupuestar la revisión.** Añadir ubicación y servicio en primer plano a una
   app publicada dispara revisión manual: días, no horas. Planifícalo antes de
   comprometer una fecha con nadie.

---

## 5. ⚠️ Migración de datos — el riesgo real de este refactor

Esto es lo que de verdad puede hacer daño, y no aparece en ninguna comprobación
automática de Play.

**Qué guarda hoy la app publicada:**

| Qué | Dónde exactamente | Formato |
|---|---|---|
| Índice de fotos | `SharedPreferences` de nombre `CapacitorStorage`, clave `photos` | JSON: `[{webPath, filePath, webViewPath}]` |
| Fotos | `Directory.Data` de Capacitor = `filesDir` de la app | JPEG, nombre `<epochMillis>.jpeg` |

Origen: `src/app/services/photo.service.ts`.

**Por qué importa.** La galería del productor es lo único que la app publicada
guarda, y es trabajo suyo. Si la versión nueva arranca con Room vacío, cada
usuario que actualice abre la app y ve que se le borró todo. No hay forma de
recuperarlo después ni de disculparse por ello.

**Qué hay que implementar** — `:core:data`, ejecutado una sola vez al arrancar,
antes de la primera pantalla:

1. Leer `SharedPreferences("CapacitorStorage")`, clave `photos`.
2. Parsear el JSON de forma tolerante: campo ausente, ruta nula, entrada
   duplicada, archivo inexistente. **Ninguno de esos casos puede abortar la
   migración entera** — una entrada mala no puede costar las otras treinta y nueve.
3. Para cada foto que exista en disco: crear una fila `CaptureEntity` con
   `gateOutcome = UNCERTAIN`, `modelVersion = "legacy-tm-1.0"`, `syncState = SKIPPED`
   (no hubo consentimiento de entrenamiento: pedirlo después, nunca asumirlo) y la
   marca temporal derivada del nombre del archivo.
4. Generar miniatura de 256 px, que la app anterior no tenía.
5. Marcar la migración como hecha en DataStore, con un contador de importadas y
   otro de descartadas.
6. **No borrar nada de `CapacitorStorage`.** Se conserva intacto. Si la migración
   resultó mal, la próxima versión todavía puede releerla; si se borra, no.

**Cómo se prueba** (obligatorio antes de publicar):

- Usuario con 0 fotos.
- Usuario con 1.
- Usuario con 40.
- JSON corrupto a media cadena.
- Entradas cuyo archivo ya no existe en disco.
- Migración interrumpida a la mitad y reejecutada: debe ser idempotente.
- Segunda ejecución tras una migración completa: no duplica nada.

---

## 6. Modelo de IA — el refactor no puede llevárselo tal cual

La app publicada usa Teachable Machine sobre TensorFlow.js, dentro de la WebView:

- `src/assets/modelos/model.json` (444 KB) + `weights.bin` (2,1 MB)
- Entrada 224×224 — coincide con la especificación nueva.
- Seis clases: `Deficiencia_Azufre`, `Deficiencia_Potasio`, `Enfermedad_Antracnosis`,
  `Enfermedad_ManchadeHierro`, `Enfermedad_OjodeGallo`, `Enfermedad_Roya`.

**Tres cosas le faltan** para la arquitectura de §7:

1. **No tiene clase de rechazo.** Ante una hoja sana o una deficiencia no listada,
   el modelo está obligado a elegir una de las seis. Es el fallo que §2.2 señala
   como más caro.
2. **No exporta embedding.** Sin la segunda cabeza no hay distancia de Mahalanobis,
   y por tanto **la compuerta 2 no puede existir**. Tampoco hay agrupación por
   similitud en el servidor. Es el error prohibido nº 3 (§23) ya presente hoy.
3. **No tiene artefactos de calibración.** Sin temperatura, τ, δ ni estadísticas
   por clase, la compuerta 3 tampoco.

**Consecuencia práctica:** el refactor puede portar el modelo actual convertido a
LiteRT para no quedarse sin escaneo, pero **con las compuertas 2 y 3 desactivadas
y diciéndolo en la interfaz**. La detección de plaga desconocida —que es la tesis
central del producto— no llega hasta que exista un modelo reentrenado con doble
salida y clase de rechazo (Fases 0 y 7).

Prometer detección de desconocidos sobre el modelo actual sería vender algo que no
está ahí.

---

## 7. ⚠️ Responsabilidad profesional en el catálogo actual

`src/assets/data/data.json` distribuye hoy recomendaciones fitosanitarias
concretas — «uso de fungicidas como el óxido de cobre» — con referencia
bibliográfica pero **sin aval de ingeniero agrónomo con tarjeta profesional
vigente**.

§13.6 del documento de arquitectura y el error prohibido nº 15 lo señalan como
exposición civil e incumplimiento ante el ICA.

El modelo de datos nuevo (`PestTaxon.approval`) tiene los campos para arreglarlo, y
`PestTaxon.showsRecommendations` oculta la sección de manejo mientras el aval no
exista. **Al migrar las seis fichas, el campo de aval va vacío** hasta que un
agrónomo las firme. Es una decisión deliberada: es preferible una ficha sin sección
de manejo a una con dosis que nadie respalda.

---

## 8. Invariantes y qué pasa si se rompen

| Elemento | Regla | Consecuencia de romperlo |
|---|---|---|
| `applicationId` | `io.ionic.starter`. Nunca se modifica. | Publicas una app distinta. Los usuarios actuales quedan huérfanos, sin ruta de actualización. Irreversible. |
| Keystore / firma | Se conserva la original, o Play App Signing | Play rechaza el AAB. Sin la clave de carga no hay forma de volver a publicar. |
| `versionCode` | Estrictamente creciente, nunca reutilizado | Play rechaza la subida. Un código quemado no se libera jamás. |
| `minSdkVersion` | 22. No sube sin análisis de impacto | Dispositivos en campo dejan de recibir actualizaciones, en silencio. |
| `MainActivity` exportada | Conserva su papel de LANZADOR | El icono desaparece del cajón de aplicaciones. |
| Autoridad del FileProvider | `io.ionic.starter.fileprovider` | Cualquier URI persistida deja de resolver. |
| Datos de `CapacitorStorage` | Se migran, no se ignoran (§5) | El productor pierde su galería completa al actualizar. |
| Esquema Room | Migraciones incrementales probadas. `fallbackToDestructiveMigration` prohibido | Se borran capturas tomadas sin señal y aún no subidas. |

---

## 9. Anexo A — `aapt2 dump badging` de la versión publicada

```
TODO: pegar la salida íntegra de
      aapt2 dump badging app-release.aab
```

## 10. Anexo B — Manifiesto fusionado publicado

```xml
TODO: pegar manifest-merger-release-report.txt
      o la salida de bundletool dump manifest
```

## 11. Anexo C — Huellas del certificado

```
TODO: pegar la salida de keytool -list -v
      o las dos huellas copiadas de Play Console
```

## 12. Anexo D — Manifiesto fuente publicado (verificado)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode"
            android:name=".MainActivity"
            android:label="@string/title_activity_main"
            android:theme="@style/AppTheme.NoActionBarLaunch"
            android:launchMode="singleTask"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>

    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

---

*Arquitectura y estructura: **Diego Fernando Calderón Silva**, arquitecto de software.*
*Certificado de firma de la versión publicada a nombre de Janier Ballesteros.*
*Documento derivado de SC-ARQ-2026-001 §2.*
