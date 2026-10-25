import 'package:flutter/material.dart';

class FlowchartScreen extends StatefulWidget {
  const FlowchartScreen({super.key});

  @override
  State<FlowchartScreen> createState() => _FlowchartScreenState();
}

class _FlowchartScreenState extends State<FlowchartScreen> {
  String selectedMethod = 'PURE_EXTENDED_ARM';

  @override
  Widget build(BuildContext context) {
    final pureRuleOnly = selectedMethod == 'PURE_EXTENDED_ARM';
    final steps = pureRuleOnly
        ? const <_FlowStep>[
            _FlowStep(
              '1',
              'SQL input and tokenization',
              'Extract base items, SQL tokens, and token order.',
              Icons.cleaning_services_outlined,
            ),
            _FlowStep(
              '2',
              'Extended ARM rule mining',
              'Mine token, negative-item, and sequential rules from training data.',
              Icons.account_tree_outlined,
            ),
            _FlowStep(
              '3',
              'Match rules',
              'Find matching rules and take the highest confidence.',
              Icons.rule,
            ),
            _FlowStep(
              '4',
              'Recommend action',
              'Confidence at or above the Validation-calibrated threshold recommends block; otherwise review.',
              Icons.gpp_maybe_outlined,
            ),
          ]
        : const <_FlowStep>[
            _FlowStep(
              '1',
              'SQL input and feature extraction',
              'Extract base SQL items, tokens, and token order.',
              Icons.cleaning_services_outlined,
            ),
            _FlowStep(
              '2',
              'Extended ARM rule mining',
              'Mine token, negative-item, and sequential rules from training data.',
              Icons.account_tree_outlined,
            ),
            _FlowStep(
              '3',
              'Rule features',
              'Match extended rules and append matching confidence values to base features.',
              Icons.rule,
            ),
            _FlowStep(
              '4',
              'Incremental ML prediction',
              'Classify using base and extended-rule features.',
              Icons.psychology_outlined,
            ),
            _FlowStep(
              '5',
              'Verified feedback',
              'Update the incremental classifier with verified labels.',
              Icons.feedback_outlined,
            ),
          ];

    return Scaffold(
      appBar: AppBar(title: const Text('Extended ARM Method Workflow')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Request processing pipeline',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'Choose one of the two project methods. Pure Extended ARM relies only on mined rules; the hybrid method adds an Incremental ML classifier that can learn from verified feedback.',
          ),
          const SizedBox(height: 16),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(
                value: 'PURE_EXTENDED_ARM',
                label: Text('Pure Extended ARM'),
                icon: Icon(Icons.rule),
              ),
              ButtonSegment(
                value: 'EXTENDED_ARM',
                label: Text('Extended ARM + ML'),
                icon: Icon(Icons.psychology_outlined),
              ),
            ],
            selected: {selectedMethod},
            onSelectionChanged: (selection) =>
                setState(() => selectedMethod = selection.first),
          ),
          const SizedBox(height: 24),
          for (var index = 0; index < steps.length; index++) ...[
            _StepCard(step: steps[index]),
            if (index < steps.length - 1)
              const Center(child: Icon(Icons.arrow_downward, size: 28)),
          ],
          const SizedBox(height: 20),
          if (!pureRuleOnly)
            const Card(
              child: ListTile(
                leading: Icon(Icons.info_outline),
                title: Text('Incremental model updates'),
                subtitle: Text(
                  'Pure rules remain fixed. Only the hybrid method updates its classifier; verified feedback is applied after each replay prediction.',
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _FlowStep {
  final String number;
  final String title;
  final String description;
  final IconData icon;
  const _FlowStep(this.number, this.title, this.description, this.icon);
}

class _StepCard extends StatelessWidget {
  final _FlowStep step;
  const _StepCard({required this.step});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: CircleAvatar(child: Text(step.number)),
        title: Text(step.title),
        subtitle: Text(step.description),
        trailing: Icon(step.icon),
      ),
    );
  }
}
