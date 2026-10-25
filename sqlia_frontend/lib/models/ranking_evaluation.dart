bool _unit(dynamic value) =>
    value is num && value.isFinite && value >= 0 && value <= 1;

Map<String, dynamic> _map(dynamic value) {
  if (value is! Map) {
    throw const FormatException('Invalid ranking evaluation object.');
  }
  return Map<String, dynamic>.from(value);
}

void validateRankingEvaluation(Map<String, dynamic> result) {
  final metrics = _map(result['metrics']);
  final intervals = _map(result['confidenceIntervals']);
  if (!metrics.containsKey('rocAuc') &&
      !intervals.containsKey('rocAuc') &&
      !result.containsKey('rankingCurves')) {
    return;
  }
  final auc = metrics['rocAuc'];
  final positives = (metrics['tp'] as int) + (metrics['fn'] as int);
  final negatives = (metrics['fp'] as int) + (metrics['tn'] as int);
  if (!metrics.containsKey('rocAuc') ||
      !(auc == null || _unit(auc)) ||
      (auc == null) != (positives == 0 || negatives == 0)) {
    throw const FormatException('Invalid or undefined ROC-AUC.');
  }
  final ci = _map(intervals['rocAuc']);
  final lo = ci['lower'], hi = ci['upper'];
  final valid = ci['validReplicates'], undefined = ci['undefinedReplicates'];
  if (!ci.containsKey('lower') ||
      !ci.containsKey('upper') ||
      !((lo == null && hi == null) ||
          (_unit(lo) && _unit(hi) && (lo as num) <= (hi as num))) ||
      valid is! int ||
      undefined is! int ||
      valid < 0 ||
      undefined < 0 ||
      valid + undefined < 200 ||
      valid + undefined > 2000 ||
      (auc == null && valid != 0) ||
      (valid == 0) != (lo == null)) {
    throw const FormatException('Invalid ROC-AUC confidence interval.');
  }
  final curves = _map(result['rankingCurves']);
  final points = curves['points'];
  final groups = curves['totalScoreGroups'];
  if (curves['displayPolicy'] is! String ||
      (curves['displayPolicy'] as String).trim().isEmpty ||
      groups is! int ||
      groups <= 0 ||
      groups > positives + negatives ||
      points is! List ||
      points.length != (groups > 400 ? 400 : groups) + 1) {
    throw const FormatException('Invalid ranking curve metadata.');
  }
  void validatePoint(Map<String, dynamic> point) {
    if (!point.containsKey('threshold') ||
        !point.containsKey('fpr') ||
        !point.containsKey('recall') ||
        !(point['fpr'] == null || _unit(point['fpr'])) ||
        !(point['recall'] == null || _unit(point['recall'])) ||
        (point['fpr'] == null) != (negatives == 0) ||
        (point['recall'] == null) != (positives == 0) ||
        !_unit(point['precision'])) {
      throw const FormatException('Invalid ranking curve point.');
    }
  }

  num lastFpr = -1, lastRecall = -1, lastThreshold = double.infinity;
  for (var i = 0; i < points.length; i++) {
    final point = _map(points[i]);
    validatePoint(point);
    final threshold = point['threshold'];
    if (i == 0) {
      if (threshold != null ||
          (positives > 0 && point['recall'] != 0) ||
          (negatives > 0 && point['fpr'] != 0)) {
        throw const FormatException('Invalid ranking curve origin.');
      }
    } else {
      if (!_unit(threshold) || (threshold as num) >= lastThreshold) {
        throw const FormatException('Invalid curve threshold ordering.');
      }
      lastThreshold = threshold;
    }
    if (positives > 0) {
      final recall = point['recall'] as num;
      if (recall < lastRecall) {
        throw const FormatException('Non-monotonic curve recall.');
      }
      lastRecall = recall;
    }
    if (negatives > 0) {
      final fpr = point['fpr'] as num;
      if (fpr < lastFpr) {
        throw const FormatException('Non-monotonic curve FPR.');
      }
      lastFpr = fpr;
    }
  }
  if ((positives > 0 && lastRecall != 1) || (negatives > 0 && lastFpr != 1)) {
    throw const FormatException('Ranking curve is missing its endpoint.');
  }
  final operating = _map(curves['operatingPoint']);
  validatePoint(operating);
  final threshold = operating['threshold'];
  final precision = (metrics['tp'] as int) + (metrics['fp'] as int) == 0
      ? 1
      : metrics['precision'];
  if (threshold is! num ||
      !threshold.isFinite ||
      threshold < 0 ||
      threshold > 1.0000000000000002 ||
      threshold != result['threshold'] ||
      operating['fpr'] != metrics['fpr'] ||
      operating['recall'] != metrics['recall'] ||
      operating['precision'] != precision) {
    throw const FormatException('Operating point differs from saved metrics.');
  }
}
