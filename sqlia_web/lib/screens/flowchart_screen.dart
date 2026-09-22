import 'package:flutter/material.dart';

class FlowchartScreen extends StatelessWidget {
  const FlowchartScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final steps = <_FlowStep>[
      const _FlowStep(
        '1',
        'Preprocessing',
        'Normalize, decode, parse, and validate the request.',
        Icons.cleaning_services_outlined,
      ),
      const _FlowStep(
        '2',
        'Base feature extraction',
        'Create ratio, syntax, and context features.',
        Icons.tune,
      ),
      const _FlowStep(
        '3',
        'Normal profile matching',
        'Compare the request with trusted benign feature patterns.',
        Icons.account_tree_outlined,
      ),
      const _FlowStep(
        '4',
        'Feature fusion',
        'Join base features with rule match, violation, and anomaly scores.',
        Icons.merge_type,
      ),
      const _FlowStep(
        '5',
        'Incremental ML classifier',
        'Predict SQLi or benign, then evaluate trusted feedback.',
        Icons.psychology_outlined,
      ),
    ];

    return Scaffold(
      appBar: AppBar(title: const Text('Project Flow')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Runtime pipeline',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'The live application follows the same stages as the project architecture diagram.',
          ),
          const SizedBox(height: 24),
          for (var index = 0; index < steps.length; index++) ...[
            _StepCard(step: steps[index]),
            if (index < steps.length - 1)
              const Center(child: Icon(Icons.arrow_downward, size: 28)),
          ],
          const SizedBox(height: 20),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: _FeedbackCard(
                  title: 'Verified benign',
                  color: Colors.green,
                  lines: const [
                    'Update ML classifier',
                    'Update normal profile',
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: _FeedbackCard(
                  title: 'Verified SQLi',
                  color: Colors.red,
                  lines: const [
                    'Update ML classifier',
                    'Do not update normal profile',
                  ],
                ),
              ),
            ],
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

class _FeedbackCard extends StatelessWidget {
  final String title;
  final Color color;
  final List<String> lines;
  const _FeedbackCard({
    required this.title,
    required this.color,
    required this.lines,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      color: color.withValues(alpha: 0.10),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: TextStyle(fontWeight: FontWeight.bold, color: color),
            ),
            const SizedBox(height: 8),
            for (final line in lines) Text('• $line'),
          ],
        ),
      ),
    );
  }
}
