import 'external_report_download_stub.dart'
    if (dart.library.html) 'external_report_download_web.dart'
    as platform;

Future<bool> saveExternalReportJson(String json, String filename) =>
    platform.saveExternalReportJson(json, filename);
