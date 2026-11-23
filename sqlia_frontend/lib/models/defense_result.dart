class DefenseProduct {
  final int id, priceCents;
  final String name, category;

  DefenseProduct.fromJson(Map<String, dynamic> json)
    : id = json['id'] as int,
      priceCents = json['priceCents'] as int,
      name = json['name'] as String,
      category = json['category'] as String;
}

class DefenseResult {
  final String requestId,
      timestamp,
      mode,
      architecture,
      algorithm,
      modelVersion;
  final String status;
  final String? recommendedAction, effectiveAction, reason, errorCode;
  final int inputLength;
  final bool businessQueryAttempted, businessQueryCompleted;
  final double? score, threshold;
  final double detectionDurationMs, queryDurationMs, totalDurationMs;
  final List<Map<String, dynamic>> matchedRules;
  final List<DefenseProduct> products;

  DefenseResult.fromJson(Map<String, dynamic> json)
    : requestId = json['requestId'] as String,
      timestamp = json['timestamp'] as String,
      mode = json['mode'] as String,
      architecture = json['architecture'] as String,
      algorithm = json['algorithm'] as String,
      modelVersion = json['modelVersion'] as String,
      status = json['status'] as String,
      recommendedAction = json['recommendedAction'] as String?,
      effectiveAction = json['effectiveAction'] as String?,
      reason = json['reason'] as String?,
      errorCode = json['errorCode'] as String?,
      inputLength = json['inputLength'] as int,
      businessQueryAttempted = json['businessQueryAttempted'] as bool,
      businessQueryCompleted = json['businessQueryCompleted'] as bool,
      score = (json['score'] as num?)?.toDouble(),
      threshold = (json['threshold'] as num?)?.toDouble(),
      detectionDurationMs = (json['detectionDurationMs'] as num).toDouble(),
      queryDurationMs = (json['queryDurationMs'] as num).toDouble(),
      totalDurationMs = (json['totalDurationMs'] as num).toDouble(),
      matchedRules = (json['matchedRules'] as List)
          .map((value) => Map<String, dynamic>.from(value as Map))
          .toList(),
      products = (json['products'] as List)
          .map(
            (value) => DefenseProduct.fromJson(
              Map<String, dynamic>.from(value as Map),
            ),
          )
          .toList();
}
