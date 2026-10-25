import 'package:flutter/material.dart';

import 'extended_rules_screen.dart';
import 'rules_chart_screen.dart';

// Extended ARM rules and their mining evaluation.
class AssociationRulesScreen extends StatelessWidget {
  final bool extendedOnly;
  const AssociationRulesScreen({super.key, this.extendedOnly = false});

  @override
  Widget build(BuildContext context) {
    final tabs = extendedOnly
        ? const [Tab(text: 'Extended ARM Rules'), Tab(text: 'Rule Ablation')]
        : const [
            Tab(text: 'FP-Growth Rules'),
            Tab(text: 'Extended ARM Rules'),
            Tab(text: 'Rule Ablation'),
          ];
    final views = extendedOnly
        ? const [
            ExtendedRulesScreen(section: ExtendedSection.rules),
            ExtendedRulesScreen(section: ExtendedSection.evaluation),
          ]
        : const [
            RulesChartScreen(),
            ExtendedRulesScreen(section: ExtendedSection.rules),
            ExtendedRulesScreen(section: ExtendedSection.evaluation),
          ];
    return DefaultTabController(
      length: tabs.length,
      child: Scaffold(
        appBar: AppBar(
          title: Text(
            extendedOnly ? 'Extended ARM Rule Analysis' : 'All Rule Analysis',
          ),
          bottom: TabBar(tabs: tabs),
        ),
        body: TabBarView(children: views),
      ),
    );
  }
}
