import 'package:flutter/material.dart';

class ArchitectureModeSelectionScreen extends StatelessWidget {
  final String functionTitle;
  final ValueChanged<bool> onSelectMode;

  const ArchitectureModeSelectionScreen({
    super.key,
    required this.functionTitle,
    required this.onSelectMode,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(functionTitle)),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Choose a comparison mode',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 16),
          _ModeCard(
            title: 'A. All architectures',
            description: 'View and compare all five detection architectures.',
            icon: Icons.account_tree_outlined,
            onTap: () => onSelectMode(false),
          ),
          _ModeCard(
            title: 'B. Two Extended ARM methods',
            description: 'Compare Pure Extended ARM with Extended ARM + Incremental Learning.',
            icon: Icons.compare_arrows,
            onTap: () => onSelectMode(true),
          ),
        ],
      ),
    );
  }
}

class _ModeCard extends StatelessWidget {
  final String title;
  final String description;
  final IconData icon;
  final VoidCallback onTap;

  const _ModeCard({
    required this.title,
    required this.description,
    required this.icon,
    required this.onTap,
  });

  @override
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
