import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

class ArchitectureIntroScreen extends StatelessWidget {
  final bool extendedOnly;
  const ArchitectureIntroScreen({super.key, this.extendedOnly = false});

  static const allArchitectures = [
    _Architecture(
      'Normal Profile-based Anomaly Detection',
      'assets/architectures/normal_profile_anomaly.svg',
    ),
    _Architecture(
      'Feature Extraction + Incremental ML',
      'assets/architectures/feature_extraction_incremental_ml.svg',
    ),
    _Architecture(
      'FP-Growth + Normal Profile + Incremental ML',
      'assets/architectures/hybrid_fpgrowth_incremental_ml.svg',
    ),
    _Architecture(
      'Extended ARM + Incremental Learning',
      'assets/architectures/extended_arm_defense.svg',
    ),
    _Architecture(
      'Pure Extended ARM (Rule Only)',
      'assets/architectures/pure_extended_arm.svg',
    ),
  ];

  static const extendedArchitectures = [
    _Architecture(
      'Pure Extended ARM (Rule Only)',
      'assets/architectures/pure_extended_arm.svg',
    ),
    _Architecture(
      'Extended ARM + Incremental Learning',
      'assets/architectures/extended_arm_defense.svg',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final architectures = extendedOnly
        ? extendedArchitectures
        : allArchitectures;
    return Scaffold(
      appBar: AppBar(
        title: Text(
          extendedOnly
              ? 'Extended ARM Method Diagrams'
              : 'All Architecture Diagrams',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            extendedOnly
                ? 'Explore the two project methods'
                : 'Explore all five detection architectures',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          Text(
            extendedOnly
                ? 'Pure Extended ARM predicts from matched-rule confidence. The hybrid method adds an Incremental ML classifier using those rule features.'
                : 'The system includes three supporting architectures and two Extended ARM methods. The Extended ARM diagrams are also available separately.',
          ),
          const SizedBox(height: 20),
          for (final architecture in architectures)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: FilledButton.icon(
                icon: const Icon(Icons.account_tree_outlined),
                label: Text(architecture.title),
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) =>
                        _ArchitectureImageScreen(architecture: architecture),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _Architecture {
  final String title;
  final String assetPath;
  const _Architecture(this.title, this.assetPath);
}

class _ArchitectureImageScreen extends StatelessWidget {
  final _Architecture architecture;
  const _ArchitectureImageScreen({required this.architecture});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(architecture.title)),
      body: InteractiveViewer(
        minScale: 0.5,
        maxScale: 4,
        child: Center(
          child: SvgPicture.asset(
            architecture.assetPath,
            fit: BoxFit.contain,
            errorBuilder: (_, _, _) => Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                'Image not found. Add the file here:\n${architecture.assetPath}',
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
