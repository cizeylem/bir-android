package com.google.android.finsky.externalreferrer;

import android.os.Bundle;

// Paket ve arayüz adı Binder descriptor'ıdır, DEĞİŞTİRİLEMEZ. Tek metot olmalı ve ilk sırada kalmalı:
// transaction kodu 1 (FIRST_CALL_TRANSACTION). Kaynak: installreferrer-2.2 bytecode'u.
interface IGetInstallReferrerService {
    Bundle getInstallReferrer(in Bundle request);
}
