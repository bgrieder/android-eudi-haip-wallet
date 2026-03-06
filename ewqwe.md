# Running the wallet with the ewQwe Credential Demo Web App

:warning: **IMPORTANT: FOR TESTING ONLY** :warning:
The modifications described in this document are strictly for development and testing purposes. They intentionally weaken the security of the application to allow interoperability with development environments that use self-signed certificates or untrusted CAs. **These changes MUST NOT be used in a production environment.**

---

## Setup and Demo Instructions

To test the HAIP wallet with a local development environment, follow these steps:

### 1. Create and Start the Emulator
You must use a rootable emulator image (Google APIs, non-Play Store) and start it with a writable system partition to allow host redirection.

*   Run the following script from the project root:
    ```bash
    ./start_ewqwe_eudi_emulator.sh
    ```
*   **What this script does:**
    1.  Automatically installs the correct Android system image if missing.
    2.  Creates a custom AVD named `EUDI_Dev_Device` using a **Pixel 9** profile.
    3.  Enables **hardware keyboard passthrough** so you can type using your computer.
    4.  Starts the emulator with the `-writable-system` flag.
    5.  **Important:** It maps the hostname `ewqwe.local` inside the emulator to your machine's actual local IP address.

### 2. Run the Application
*   Open the project in Android Studio.
*   Select the `app` module and the `EUDI_Dev_Device` emulator.
*   Click **Run** to deploy and start the EUDI Wallet App on the device.

### 3. Initialize Documents
*   Follow the on-screen instructions to create a **PIN Code**.
*   **Obtaining Test Credentials:** Test documents and credentials can be obtained from the **EUDIW Testing Issuer** at `https://issuer.eudiw.dev`.
*   Tap the **"+"** icon in the Wallet and select **"Add a Document from List"**.
*   From the list provided by `https://issuer.eudiw.dev`, select:
    *   **"mDL (MSO MDOC)"**
    *   **"PID (MSO MDOC)"**
*   When prompted for the "country of origin", select **"Form EU"** as the country of origin.
*   Fill in the test form with any data, submit it, and authorize the issuance.

### 4. Open the Relying Party Demo Webapp
*   Start your local **ewqwe demo webapp** (following the instructions in its own repository).
*   Open the **Chrome** browser on the Android Emulator.
*   Navigate to: `https://ewqwe.local:5174`. 
*   **Note:** You may see a certificate warning; you can safely proceed ("Advanced" -> "Proceed to ewqwe.local").

### 5. Request HAIP Credentials (Scanning QR)
From the webapp, initiate a request for HAIP credentials. If the webapp shows a QR code, you have two options to scan it:

#### Option A: Virtual Scene (Supply an Image)
1.  Open **Extended Controls (...)** -> **Camera**.
2.  In **Virtual scene images**, upload your QR code to the **Wall** or **Table**.
3.  In the emulator app, open the scanner.
4.  **Navigation Cheat Sheet:**
    *   **Hold Alt + Move Mouse:** Look around the room.
    *   **W / S / A / D:** Walk forward, backward, or strafe.
    *   **Find the Wall Code:** Look to the **left** for the wall with the TV.
    *   **Find the Table Code:** Look **down** at the coffee table near the cat.
5.  Move close to the code until it scans.

#### Option B: Use your Computer's Webcam
If you have a physical webcam, you can point it at a QR code displayed on your phone:
1.  Open **Extended Controls (...)** -> **Camera**.
2.  Set **Back camera** to your laptop's webcam (e.g., "FaceTime HD Camera").
3.  Hold your phone (with the QR code) up to your laptop's camera.

---

## Technical Overview of Modifications

In a production environment, the EUDI Wallet strictly validates the identity of the parties it communicates with. During development, these parties often use self-signed certificates. We have implemented "Soft Trust" bypasses to facilitate testing.

### 1. Untrusted TLS Certificates (Network Layer)
The `HttpClient` bypasses standard X509 certificate validation to allow connections to local development servers.
*   **File:** `network-logic/.../di/NetworkModule.kt`

### 2. Untrusted OpenID4VP Request Signers (Reader Trust)
A `SoftReaderTrustStore` bypasses the `x5c` chain validation against trusted IACAs and logs a warning instead.
*   **File:** `core-logic/.../util/SoftReaderTrustStore.kt`
*   **Integration:** `core-logic/.../di/LogicCoreModule.kt`

### 3. Client ID Bypass (SAN Injection)
OpenID4VP requires that the `client_id` matches a Subject Alternative Name (SAN) in the certificate. `SoftReaderTrustStore` wraps certificates to dynamically inject common development SANs (like `127.0.0.1`, `localhost`, `192.168.1.x`).
*   **File:** `core-logic/.../util/SoftReaderTrustStore.kt`
