import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/association_rule.dart';
import '../providers/detection_provider.dart';

class RuleExplorerScreen extends ConsumerStatefulWidget {
  const RuleExplorerScreen({super.key});

  @override
  // Returns the state object for this widget.
  ConsumerState<RuleExplorerScreen> createState() =>
      _RuleExplorerScreenState();
}
// The state class for [RuleExplorerScreen] that manages the selected rule set, loading state, error messages, and the list of association rules.
class _RuleExplorerScreenState extends ConsumerState<RuleExplorerScreen> {
  String selectedRuleSet = 'stage4-v1-fpgrowth-01-06';
  bool isLoading = false;
  String? errorMessage;
  List<AssociationRule> rules = [];
  // A mapping of rule set names to their corresponding IDs, used for populating the dropdown menu in the UI.
  final ruleSets = const {
    'FP-Growth: minSup 0.1, minConf 0.6':
        'stage4-v1-fpgrowth-01-06',
    'FP-Growth: minSup 0.1, minConf 0.8':
        'stage4-v1-fpgrowth-01-08',
    'FP-Growth: minSup 0.05, minConf 0.6':
        'stage4-v1-fpgrowth-005-06',
    'FP-Growth: minSup 0.05, minConf 0.8':
        'stage4-v1-fpgrowth-005-08',
    'Eclat: minSup 0.1':
        'stage4-v1-eclat-01',
    'Eclat: minSup 0.05':
        'stage4-v1-eclat-005',
  };

  @override
  // Initialize the state of the widget and load the association rules for the selected rule set.
  void initState() {
    super.initState();
    loadRules();
  }
  // Loads the association rules for the selected rule set from the API and updates the state accordingly. It handles loading state, error messages, and updates the list of rules.
  Future<void> loadRules() async {
    setState(() {
      isLoading = true;
      errorMessage = null;
    });
    // Fetch the rules from the API using the RuleService provided by Riverpod. If successful, update the list of rules; if an error occurs, set the error message. Finally, set the loading state to false.
    try {
      final loadedRules = await ref
          .read(ruleServiceProvider)
          .getRules(selectedRuleSet);

      if (!mounted) {
        return;
      }

      setState(() {
        rules = loadedRules;
      });
    } catch (error) {
      if (!mounted) {
        return;
      }

      setState(() {
        errorMessage = error.toString();
      });
    } finally {
      if (mounted) {
        setState(() {
          isLoading = false;
        });
      }
    }
  }
  // Converts an item integer to its corresponding name string based on a predefined mapping. If the item is not found in the mapping, it returns a default string with the item value.
  String itemName(int item) {
    const names = {
      1: 'LONG_LENGTH',
      2: 'MANY_VARIABLES',
      3: 'HAS_WHERE',
      33: 'MULTIPLE_WHERE',
      4: 'HAS_SELECT',
      44: 'MULTIPLE_SELECT',
      5: 'HAS_UNION',
      55: 'MULTIPLE_UNION',
      6: 'RISKY_QUOTE',
      7: 'HAS_COMMENT',
      8: 'HIGH_OR',
      100: 'BENIGN',
      101: 'MALICIOUS',
    };

    return names[item] ?? 'ITEM_$item';
  }

  String formatItems(List<int> items) {
    return items.map(itemName).join(' AND ');
  }

  @override
  // Build the UI for the Rule Explorer screen, including a dropdown to select rule sets, a loading indicator, error messages, and a list of association rules.
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Association Rule Explorer')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            DropdownButtonFormField<String>(
              initialValue: selectedRuleSet,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Rule set',
              ),
              items: ruleSets.entries.map((entry) {
                return DropdownMenuItem(
                  value: entry.value,
                  child: Text(entry.key),
                );
              }).toList(),
              onChanged: isLoading
                  ? null
                  : (value) {
                      if (value == null) {
                        return;
                      }

                      setState(() {
                        selectedRuleSet = value;
                      });

                      loadRules();
                    },
            ),
            const SizedBox(height: 20),
            if (isLoading) const LinearProgressIndicator(),
            if (errorMessage != null) ...[
              const SizedBox(height: 16),
              Text(
                errorMessage!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 12),
            Expanded(
              child: rules.isEmpty && !isLoading
                  ? const Center(child: Text('No rules found.'))
                  : ListView.separated(
                      itemCount: rules.length,
                      separatorBuilder: (_, _) =>
                          const SizedBox(height: 8),
                      itemBuilder: (context, index) {
                        final rule = rules[index];
                        final isItemset = rule.consequent.isEmpty;

                        return Card(
                          child: ListTile(
                            title: Text(
                              isItemset
                                  ? formatItems(rule.antecedent)
                                  : '${formatItems(rule.antecedent)}  ->  '
                                      '${formatItems(rule.consequent)}',
                            ),
                            subtitle: Text(
                              isItemset
                                  ? 'Support: ${rule.support}'
                                  : 'Support: ${rule.support}    '
                                      'Confidence: '
                                      '${(rule.confidence * 100).toStringAsFixed(2)}%',
                            ),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}