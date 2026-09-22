import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../providers/detection_provider.dart';

// A screen that allows users to input an SQL query and displays the detection result.
class DetectScreen extends ConsumerStatefulWidget{
  // Creates an instance of [DetectScreen].
  const DetectScreen({super.key});
  // Returns the state object for this widget.
  @override
  ConsumerState<DetectScreen> createState() => _DetectScreenState();
}
// The state class for [DetectScreen] that manages the input and detection result.
class _DetectScreenState extends ConsumerState<DetectScreen>{
  final controller = TextEditingController();
  String? submittedSql;
  @override
  Widget build(BuildContext context){
    final resultAsync = submittedSql == null ? null : ref.watch(detectResultProvider(submittedSql!));
    return Scaffold(
      appBar : AppBar(title: const Text('SQL Injection Detection')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: controller,
              maxLines: 4,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Enter SQL Query',
              ), 
            ), //TextField
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: () => setState(() => submittedSql = controller.text),
              child: const Text('Detection'),
            ), //ElevatedButton
            const SizedBox(height: 24),
            if (resultAsync != null)
              resultAsync.when(
                data: (result) => _ResultCard(result: result),
                loading: () => const CircularProgressIndicator(),
                error: (e, _) => Text('Error: $e', style: const TextStyle(color: Colors.red)),
              ),
          ],
        ),
      ),
    );  
  }
}

// A widget that displays the detection result in a card format.
class _ResultCard extends StatelessWidget{
  final dynamic result;
  const _ResultCard({required this.result}); 
  @override
  Widget build(BuildContext context){
    final isMalicious = result.label == 1;
    return Card(
      color: isMalicious ? Colors.red.shade50 : Colors.green.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isMalicious ? 'Malicious SQL Detected' : 'Query is Benign',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 18,
                color: isMalicious ? Colors.red : Colors.green,
              ),
            ),
            Text('Confidence Score: ${(result.confidence * 100).toStringAsFixed(1)}%'),
            const SizedBox(height: 8),
            const Text('Feature vector:', style: TextStyle(fontWeight: FontWeight.bold)),
            ...result.features.entries.map<Widget>(
              (e) => Text('${e.key}: ${e.value}'),
            ),
          ],
        ),
      ),
    );
  }
}