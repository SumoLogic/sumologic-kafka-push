# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure('2') do |config|
  config.vm.box = 'ubuntu/focal64'
  config.vm.disk :disk, size: "50GB", primary: true
  config.vm.box_check_update = false
  config.vm.host_name = 'sumologic-kafka-push'
  config.vm.network :private_network, ip: "192.168.52.35"

  config.vm.provider 'virtualbox' do |vb|
    vb.gui = false
    vb.cpus = 8
    vb.memory = 16384
    vb.name = 'sumologic-kafka-push'
  end

  config.vm.provider "qemu" do |qe, override|
    override.vm.box = "perk/ubuntu-2204-arm64"
    qe.gui = false
    qe.smp = 8
    qe.memory = 16384
    qe.name = 'sumologic-kafka-push'
  end

  config.vm.provision 'shell', path: 'vagrant/provision.sh'

  config.vm.synced_folder ".", "/sumologic"
end
