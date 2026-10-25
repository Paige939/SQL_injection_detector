const detectionInputLabel = 'SQL, payload, or user input';
const detectionInputHint =
    'Enter SQL, an injection fragment, or a value such as a number or email.';
const detectionDisclaimer =
    'A prediction is not a safety guarantee. Existing models must be retrained '
    'before evaluating a changed dataset.';

String? validateDetectionInput(String input) {
  if (input.trim().isEmpty || input.length > 4000) {
    return 'Enter nonblank input of at most 4000 characters.';
  }
  return null;
}
