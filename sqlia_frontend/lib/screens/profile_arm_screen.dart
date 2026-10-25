import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/detection_provider.dart';

class ProfileArmScreen extends ConsumerStatefulWidget {
  const ProfileArmScreen({super.key});

  @override
  ConsumerState<ProfileArmScreen> createState() => _ProfileArmScreenState();
}

class _ProfileArmScreenState extends ConsumerState<ProfileArmScreen> {
  Map<String, dynamic>? status;
  String? error;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    refresh();
    timer = Timer.periodic(const Duration(seconds: 3), (_) => refresh());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> refresh() async {
    try {
      final value = await ref.read(incrementalServiceProvider).getStatus();
      if (mounted) {
        setState(() {
          status = value;
          error = null;
        });
      }
    } catch (value) {
      if (mounted) setState(() => error = value.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final profileSize = status?['normalProfileSize'] ?? 0;
    final trained = status?['totalTrained'] ?? 0;
    return Scaffold(
      appBar: AppBar(title: const Text('Benign Profile Status')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'Trusted normal-query profile',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text(
            'Only verified benign feedback adds queries to this profile; SQL injection feedback does not.',
          ),
          const SizedBox(height: 20),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _Metric(label: 'Queries in profile', value: '$profileSize'),
              _Metric(
                label: 'Online model training samples',
                value: '$trained',
              ),
              _Metric(
                label: 'Profile update policy',
                value: 'Verified benign only',
              ),
            ],
          ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 20),
              child: Text(
                error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          const SizedBox(height: 28),
          const Card(
            child: ListTile(
              leading: Icon(Icons.verified_user),
              title: Text('Verified feedback behavior'),
              subtitle: Text(
                'Verified benign updates ML and the normal profile. Verified SQLi updates ML only.',
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  final String label;
  final String value;
  const _Metric({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 190,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label),
            const SizedBox(height: 8),
            Text(value, style: Theme.of(context).textTheme.titleLarge),
          ],
        ),
      ),
    ),
  );
}
