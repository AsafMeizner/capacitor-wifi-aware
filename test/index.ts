/* eslint-disable @typescript-eslint/explicit-module-boundary-types */
import { WifiAware } from '../src/index';

// Function to test Wi-Fi Aware functionality
export async function testWifiAware() {
    // First check if Wi-Fi Aware is available
    console.log('Testing Wi-Fi Aware availability...');
    const result = await WifiAware.attach();
    console.log('Wi-Fi Aware available:', result.available);
    
    if (!result.available) {
        console.log('Reason:', result.reason);
        return;
    }

    // Set up event listeners
    console.log('Setting up event listeners...');
    
    const stateChangedListener = await WifiAware.addListener('stateChanged', (state) => {
        console.log('Wi-Fi Aware state changed:', state);
    });
    
    const serviceFoundListener = await WifiAware.addListener('serviceFound', (event) => {
        console.log('Service found:', event);
        console.log(`- Peer ID: ${event.peerId}`);
        console.log(`- Service Name: ${event.serviceName}`);
        if (event.serviceInfoBase64) {
            console.log(`- Service Info: ${atob(event.serviceInfoBase64)}`);
        }
        if (event.distanceMm) {
            console.log(`- Distance: ${event.distanceMm} mm`);
        }
    });
    
    const serviceLostListener = await WifiAware.addListener('serviceLost', (event) => {
        console.log('Service lost:', event);
    });
    
    const messageListener = await WifiAware.addListener('messageReceived', (msg) => {
        console.log('Message received:', msg);
        console.log(`- From: ${msg.peerId}`);
        console.log(`- Content: ${atob(msg.dataBase64)}`);
    });
    
    const socketReadyListener = await WifiAware.addListener('socketReady', (res) => {
        console.log('Socket ready:', res);
    });
    
    const socketClosedListener = await WifiAware.addListener('socketClosed', () => {
        console.log('Socket closed');
    });

    // Define the service name
    const serviceName = 'wifiaware_test';
    
    // Start publishing or subscribing based on role selection
    const role = 'publisher'; // Change to 'subscriber' to test the other role

    if (role === 'publisher') {
        console.log('Starting as publisher...');
        
        // Publisher: start publishing
        try {
            await WifiAware.publish({
                serviceName,
                serviceInfoBase64: btoa('Hello from publisher'),
                instantMode: true,
                rangingEnabled: true
            });
            console.log('Publishing started successfully');
        } catch (error) {
            console.error('Error starting publishing:', error);
        }
        
        // Wait for connections
        console.log('Waiting for subscribers to connect...');
        
    } else {
        console.log('Starting as subscriber...');
        
        // Subscriber: start subscribing
        try {
            await WifiAware.subscribe({
                serviceName,
                instantMode: true
            });
            console.log('Subscribing started successfully');
        } catch (error) {
            console.error('Error starting subscribing:', error);
        }
        
        console.log('Waiting for publishers to be discovered...');
    }

    // This is a demo - in a real app you would handle interactions 
    // between found peers and manage the lifecycle properly

    // Example of how to clean up
    function cleanup() {
        console.log('Cleaning up...');
        if (role === 'publisher') {
            WifiAware.stopPublish();
        } else {
            WifiAware.stopSubscribe();
        }
        
        // Remove all listeners
        stateChangedListener.remove();
        serviceFoundListener.remove();
        serviceLostListener.remove();
        messageListener.remove();
        socketReadyListener.remove();
        socketClosedListener.remove();
        
        console.log('Cleanup complete');
    }

    // In a real app, you would call cleanup() when appropriate
    // For this demo, we'll just log instructions
    console.log('To stop the demo, call the cleanup() function');
    
    return { cleanup };
}