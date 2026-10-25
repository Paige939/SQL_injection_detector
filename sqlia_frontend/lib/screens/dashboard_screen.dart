import 'package:flutter/material.dart';

import 'association_rules_screen.dart';
import 'architecture_intro_screen.dart';
import 'architecture_mode_selection_screen.dart';
import 'architecture_query_screen.dart';
import 'attack_simulation_screen.dart';
import 'performance_screen.dart';
import 'external_report_screen.dart';
import '../services/external_report_service.dart';

class DashboardScreen extends StatelessWidget {
  final ExternalReportsRepository? externalReportsRepository;

  const DashboardScreen({super.key, this.externalReportsRepository});

  @override
  // Builds the UI for the dashboard screen, displaying navigation cards for different security operations.
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Extended ARM SQL Injection Defense')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Detection and Evaluation',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: 8),
          const Text(
            'Choose a function, then select either all five architectures or '
            'a focused comparison of the two Extended ARM methods.',
          ),
          const SizedBox(height: 24),
          _NavigationCard(
            icon: Icons.menu_book_outlined,
            title: '1. Architecture diagrams',
            description:
                'View the system diagrams and compare their approaches.',
            onTap: () => _openModeSelection(
              context,
              'Architecture diagrams',
              (extendedOnly) =>
                  ArchitectureIntroScreen(extendedOnly: extendedOnly),
            ),
          ),
          _NavigationCard(
            icon: Icons.compare_arrows,
            title: '2. Compare one input',
            description:
                'Compare predictions for SQL, a payload, or user input.',
            onTap: () => _openModeSelection(
              context,
              'Compare one input',
              (extendedOnly) =>
                  ArchitectureQueryScreen(extendedOnly: extendedOnly),
            ),
          ),
          _NavigationCard(
            icon: Icons.rule_folder_outlined,
            title: '3. Association-rule analysis',
            description:
                'Explore mined rules, evaluation, and ablation results.',
            onTap: () => _openModeSelection(
              context,
              'Association-rule analysis',
              (extendedOnly) =>
                  AssociationRulesScreen(extendedOnly: extendedOnly),
            ),
          ),
          _NavigationCard(
            icon: Icons.bar_chart,
            title: '4. Test performance',
            description: 'Evaluate architectures on the held-out test dataset.',
            onTap: () => _openModeSelection(
              context,
              'Test performance',
              (extendedOnly) => PerformanceScreen(extendedOnly: extendedOnly),
            ),
          ),
          _NavigationCard(
            icon: Icons.play_circle_outline,
            title: '5. Replay test data',
            description:
                'Replay held-out queries and review detection results.',
            onTap: () => _openModeSelection(
              context,
              'Replay test data',
              (extendedOnly) =>
                  AttackSimulationScreen(extendedOnly: extendedOnly),
            ),
          ),
          _NavigationCard(
            icon: Icons.public_outlined,
            title: '6. External generalization',
            description:
                'Read saved frozen-checkpoint external evaluation reports; '
                'this view never trains or updates models.',
            onTap: () => _openModeSelection(
              context,
              'External generalization',
              (extendedOnly) => ExternalReportListScreen(
                extendedOnly: extendedOnly,
                repository: externalReportsRepository,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

void _openModeSelection(
  BuildContext context,
  String functionTitle,
  Widget Function(bool extendedOnly) screenBuilder,
) {
  Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => ArchitectureModeSelectionScreen(
        functionTitle: functionTitle,
        onSelectMode: (extendedOnly) => Navigator.of(
          context,
        ).push(MaterialPageRoute(builder: (_) => screenBuilder(extendedOnly))),
      ),
    ),
  );
}

// A widget that displays a card with an icon, title, description, and a tap action to navigate to another screen.
class _NavigationCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final String description;
  final VoidCallback onTap;

  const _NavigationCard({
    required this.icon,
    required this.title,
    required this.description,
    required this.onTap,
  });

  @override
  // Builds a card widget that displays a label and its corresponding value, styled according to the current theme.
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        leading: Icon(icon, size: 30),
        title: Text(title),
        subtitle: Text(description),
        trailing: const Icon(Icons.chevron_right),
        onTap: onTap,
      ),
    );
  }
}
