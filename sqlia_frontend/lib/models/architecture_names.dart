String architectureShortName(String architecture) => switch (architecture) {
  'ARM_PROFILE' => 'Profile',
  'RATIO_INCREMENTAL_ML' => 'Feature ML',
  'FULL_FLOW' => 'Full Flow',
  'EXTENDED_ARM' => 'Hybrid',
  'PURE_EXTENDED_ARM' => 'Pure ARM',
  _ => architecture,
};

String architectureDisplayName(String architecture) => switch (architecture) {
  'ARM_PROFILE' => 'Normal Profile Anomaly Detection',
  'RATIO_INCREMENTAL_ML' => 'Feature Extraction + Incremental ML',
  'FULL_FLOW' => 'Extended FP-Growth + Incremental ML',
  'EXTENDED_ARM' => 'Extended ARM + Incremental ML',
  'PURE_EXTENDED_ARM' => 'Pure Extended ARM (Rule Only)',
  _ => architecture,
};
