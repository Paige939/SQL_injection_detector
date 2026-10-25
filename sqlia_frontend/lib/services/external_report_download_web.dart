import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

Future<bool> saveExternalReportJson(String json, String filename) async {
  final options = (globalContext['Object'] as JSFunction)
      .callAsConstructor<JSObject>();
  options['type'] = 'application/json;charset=utf-8'.toJS;
  final blob = (globalContext['Blob'] as JSFunction)
      .callAsConstructorVarArgs<JSObject>([
        <JSAny?>[json.toJS].toJS,
        options,
      ]);
  final urlApi = globalContext['URL'] as JSObject;
  final objectUrl = urlApi
      .callMethod<JSString>('createObjectURL'.toJS, blob)
      .toDart;
  final document = globalContext['document'] as JSObject;
  final anchor = document.callMethod<JSObject>('createElement'.toJS, 'a'.toJS);
  anchor['href'] = objectUrl.toJS;
  anchor['download'] = filename.toJS;
  (document['body'] as JSObject).callMethod<JSAny?>('appendChild'.toJS, anchor);
  anchor.callMethod<JSAny?>('click'.toJS);
  anchor.callMethod<JSAny?>('remove'.toJS);
  Timer(
    const Duration(seconds: 1),
    () => urlApi.callMethod<JSAny?>('revokeObjectURL'.toJS, objectUrl.toJS),
  );
  return true;
}
