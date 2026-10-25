import '../core/api_client.dart';
import '../models/association_rule.dart';

class RuleService{
  final ApiClient client;
  
  RuleService(this.client);
  // Fetches a list of association rules from the API based on the provided rule set ID.
  Future<List<AssociationRule>> getRules(String ruleSetId) async{
    // Make a GET request to the '/api/rules' endpoint with the specified ruleSetId as a query parameter.
    final res = await client.dio.get(
      '/api/rules',
      queryParameters: {
        'ruleSetId': ruleSetId
      },
    );
    // Cast the response data to a list of dynamic values.
    final values = res.data as List;
    // Map each value in the list to an [AssociationRule] instance by converting the value to a JSON map and using the [AssociationRule.fromJson] factory constructor. Finally, convert the mapped values to a list and return it.
    return values.map(
      (value) => AssociationRule.fromJson(Map<String, dynamic>.from(value as Map),
      ),
    ).toList();
  }
}