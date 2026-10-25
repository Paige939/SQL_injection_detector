// Represents an association rule with antecedent, consequent, support, and confidence values.
class AssociationRule{
  final List<int> antecedent;
  final List<int> consequent;
  final int support;
  final double confidence;
  // Constructs an [AssociationRule] instance with the specified antecedent, consequent, support, and confidence values.
  const AssociationRule({
    required this.antecedent,
    required this.consequent,
    required this.support,
    required this.confidence
  });
  // Factory constructor to create an [AssociationRule] instance from a JSON map.
  factory AssociationRule.fromJson(Map<String, dynamic> json){
    return AssociationRule(
      // Extracts the antecedent and consequent lists from the JSON map, converting each value to a string.
      antecedent: (json['antecedent'] as List).map((value) => value as int).toList(),
      consequent: (json['consequent'] as List).map((value) => value as int).toList(),
      support: json['support'] as int,
      confidence: (json['confidence'] as num).toDouble()
    );
  }
}