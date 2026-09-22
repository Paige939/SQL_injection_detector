import 'package:flutter/material.dart';

import 'architecture_intro_screen.dart';
import 'architecture_query_screen.dart';
import 'rules_chart_screen.dart';
import 'performance_screen.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  // Builds the UI for the dashboard screen, displaying navigation cards for different security operations.
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('SQLIA Defense Console')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Security Operations',
            style: Theme.of(context).textTheme.headlineMedium,
          ),
          const SizedBox(height: 8),
          const Text(
            'Detect SQL injection attempts, replay labelled datasets, '
            'inspect association rules, and monitor the online model.',
          ),
          const SizedBox(height: 24),
          Text(
            'SQLIA Architecture Console',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          _NavigationCard(
            icon: Icons.menu_book_outlined,
            title: '1. Architecture Introduction',
            description: 'View the three architecture flow diagrams and responsibilities.',
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => const ArchitectureIntroScreen(),
              ),
            ),
          ),
          _NavigationCard(
            icon: Icons.compare_arrows,
            title: '2. Query Architecture Comparison',
            description:
                'Compare benign/malicious confidence for one new query.',
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => const ArchitectureQueryScreen(),
              ),
            ),
          ),
          _NavigationCard(
            icon: Icons.bar_chart,
            title: '3. FP-Growth Rules',
            description: 'Inspect rules and confidence charts for threshold combinations.',
            onTap: () => Navigator.of(
              context,
            ).push(MaterialPageRoute(builder: (_) => const RulesChartScreen())),
          ),
          _NavigationCard(
            icon: Icons.history,
            title: '4. Incremental Simulation and History',
            description:
                'Run all three architectures and compare their metric history.',
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const PerformanceScreen()),
            ),
          ),
        ],
      ),
    );
  }
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
